"""Qwen3.8-Omni-Flash audio calls over Alibaba Cloud's official Chat Completions API.

Reference: https://help.aliyun.com/zh/model-studio/qwen-omni
The API key is passed from local_config.py and is never logged here.
"""

import base64
import io
import json
import os
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path


MODEL = "qwen3.8-omni-flash"
MAX_BASE64_BYTES = 10_000_000
RETRYABLE_HTTP = {429, 500, 502, 503, 504}


class ModelResponseError(ValueError):
    """The server replied, but its completion cannot be used as a JSON object."""


class BailianClient:
    def __init__(self, api_key, base_url, *, timeout=15, retries=0, transport="auto"):
        self.api_key = api_key.strip()
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.retries = retries
        if not self.api_key or not self.base_url:
            raise ValueError("Bailian API key and base URL are required")
        if any(char in self.api_key for char in '\r\n"'):
            raise ValueError("Invalid API key characters")
        if transport not in ("auto", "urllib", "curl"):
            raise ValueError("Invalid transport")
        self.transport = ("curl" if os.name == "nt" and shutil.which("curl.exe") else "urllib") if transport == "auto" else transport

    @staticmethod
    def request_body(wav, prompt):
        if not isinstance(wav, bytes) or not wav.startswith(b"RIFF") or wav[8:12] != b"WAVE":
            raise ValueError("Audio must be WAV bytes")
        if not isinstance(prompt, str) or not prompt.strip():
            raise ValueError("A nonempty text prompt is required")
        encoded = base64.b64encode(wav)
        if len(encoded) >= MAX_BASE64_BYTES:
            raise ValueError("WAV exceeds the 10 MB Base64 input limit")
        return {
            "model": MODEL,
            "messages": [{"role": "user", "content": [
                {"type": "input_audio", "input_audio": {
                    "data": "data:audio/wav;base64," + encoded.decode("ascii"), "format": "wav"}},
                {"type": "text", "text": prompt},
            ]}],
            "modalities": ["text"],
            "response_format": {"type": "json_object"},
            "reasoning_effort": "none",
            "stream": False,
        }

    @staticmethod
    def parse_response(raw):
        try:
            response = json.loads(raw)
            content = response["choices"][0]["message"]["content"]
            if not isinstance(content, str):
                raise TypeError("Completion content must be text")
            value = json.loads(content)
            if not isinstance(value, dict):
                raise TypeError("Completion must be a JSON object")
            return value
        except (ValueError, KeyError, IndexError, TypeError) as error:
            raise ModelResponseError("Bailian returned an invalid JSON completion") from error

    def analyze(self, wav, prompt):
        body = json.dumps(self.request_body(wav, prompt), ensure_ascii=False).encode("utf-8")
        for attempt in range(self.retries + 1):
            try:
                raw = self._send_curl(body) if self.transport == "curl" else self._send_urllib(body)
                if len(raw) > 1_000_000:
                    raise ModelResponseError("Bailian response exceeds 1 MB")
                return self.parse_response(raw)
            except urllib.error.HTTPError as error:
                if error.code not in RETRYABLE_HTTP or attempt >= self.retries:
                    raise
            except (urllib.error.URLError, TimeoutError):
                if attempt >= self.retries:
                    raise
            time.sleep(1 + attempt)

    def _send_urllib(self, body):
        request = urllib.request.Request(
            self.base_url + "/chat/completions", data=body, method="POST",
            headers={"Authorization": "Bearer " + self.api_key, "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            return response.read(1_000_001)

    def _send_curl(self, body):
        """Use Windows Schannel; send the key on stdin, never in process arguments."""
        curl = shutil.which("curl.exe")
        if not curl:
            raise ValueError("curl.exe is required for the Windows TLS transport")
        descriptor, filename = tempfile.mkstemp(prefix="bailian-request-", suffix=".json")
        try:
            with os.fdopen(descriptor, "wb") as request_file:
                request_file.write(body)
            config = ("header = \"Authorization: Bearer " + self.api_key + "\"\n"
                      "header = \"Content-Type: application/json\"\n")
            command = [curl, "--silent", "--show-error", "--request", "POST",
                       "--url", self.base_url + "/chat/completions",
                       "--data-binary", "@" + filename,
                       "--write-out", "\nCURL_HTTP_STATUS:%{http_code}", "--max-time", str(self.timeout), "--config", "-"]
            try:
                completed = subprocess.run(command, input=config.encode("utf-8"),
                                           capture_output=True, timeout=self.timeout + 5)
            except subprocess.TimeoutExpired as error:
                raise urllib.error.URLError(TimeoutError("Bailian HTTPS request timed out")) from error
            if completed.returncode:
                raise urllib.error.URLError(OSError("Windows curl TLS transport failed (exit " +
                                                   str(completed.returncode) + ")"))
            try:
                raw, status_text = completed.stdout.rsplit(b"\nCURL_HTTP_STATUS:", 1)
                status = int(status_text.strip())
            except (ValueError, TypeError) as error:
                raise ModelResponseError("Windows curl returned no HTTP status") from error
            if status >= 400:
                raise urllib.error.HTTPError(self.base_url + "/chat/completions", status,
                                             "Bailian HTTP error", {}, io.BytesIO(raw))
            if status != 200:
                raise ModelResponseError("Unexpected Bailian HTTP status " + str(status))
            return raw
        finally:
            Path(filename).unlink(missing_ok=True)

