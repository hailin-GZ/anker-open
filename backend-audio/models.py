"""Local, replaceable audio providers. No model downloads during serving."""
from pathlib import Path
import re
import numpy as np
import sherpa_onnx as so


class Models:
    def __init__(self, directory):
        root = Path(directory)
        voice = root/'sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09'
        self.asr = so.OfflineRecognizer.from_sense_voice(
            model=str(voice/'model.int8.onnx'), tokens=str(voice/'tokens.txt'),
            num_threads=2, language='auto', use_itn=True)
        config = so.SpeakerEmbeddingExtractorConfig(
            model=str(root/'3dspeaker_speech_eres2net_base_200k_sv_zh-cn_16k-common.onnx'),
            num_threads=2)
        self.speaker = so.SpeakerEmbeddingExtractor(config)
        config = so.OfflineSpeakerDiarizationConfig(
            segmentation=so.OfflineSpeakerSegmentationModelConfig(
                pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(
                    model=str(root/'sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx')),
                num_threads=2), embedding=config,
            clustering=so.FastClusteringConfig(num_clusters=-1, threshold=.65))
        self.diarizer = so.OfflineSpeakerDiarization(config)
        config = so.VadModelConfig()
        config.silero_vad.model = str(root/'silero_vad.onnx')
        config.silero_vad.min_silence_duration = .4
        config.silero_vad.min_speech_duration = .2
        config.silero_vad.max_speech_duration = 8
        config.sample_rate = 16000
        self.vad_config = config

    def vad(self):
        return so.VoiceActivityDetector(self.vad_config, buffer_size_in_seconds=30)

    def speech(self, samples):
        stream = self.asr.create_stream()
        stream.accept_waveform(16000, samples)
        self.asr.decode_stream(stream)
        raw = stream.result.text
        text = re.sub(r'<\|[^|]+\|>', '', raw).strip()
        embedding = None
        if len(samples) >= 16000:  # Shorter than one second stays unknown.
            speaker = self.speaker.create_stream()
            speaker.accept_waveform(16000, samples)
            speaker.input_finished()
            if self.speaker.is_ready(speaker):
                embedding = np.asarray(self.speaker.compute(speaker), dtype=np.float32)
        language = re.sub(r'<\||\|>', '', str(stream.result.lang)).lower()
        language = {'zh': 'zh-CN', 'yue': 'yue', 'en': 'en', 'ja': 'ja', 'ko': 'ko'}.get(language, 'und')
        return text, embedding, language

    def turns(self, samples):
        """Partition at estimated speaker changes before text is finalized.

        Local diarizer IDs are never exposed as session IDs. Significant overlap
        keeps the whole text and marks its speaker unknown; no source separation.
        """
        if len(samples) < 16000:
            return [(0, len(samples), True)]
        turns = self.diarizer.process(samples).sort_by_start_time()
        if not turns:
            return [(0, len(samples), True)]
        for a, b in zip(turns, turns[1:]):
            if b.start < a.end-.15:
                return [(0, len(samples), True)]
        boundaries = [0]
        for a, b in zip(turns, turns[1:]):
            if a.speaker != b.speaker:
                boundary = int((a.end+b.start)*8000)
                if boundary-boundaries[-1] >= 4800 and len(samples)-boundary >= 4800:
                    boundaries.append(boundary)
        boundaries.append(len(samples))
        return [(a, b, False) for a, b in zip(boundaries, boundaries[1:])]

