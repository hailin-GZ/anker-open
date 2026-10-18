# 声应 · 公开源代码

包含当前1.0.3的项目自研原生界面、连续PCM上传、字幕/分析接收、候选回应、标记与存储，以及可交互网页演示和通用云端网关。

本仓库不包含任何厂商SDK源码或二进制、硬件通讯协议、厂商鉴权实现、私有API地址、真实密钥、账号密码、录音、数据库、签名文件或包含专有SDK的APK。已部署的私有生产系统不由此导出过程修改。

## 快速运行网页演示

安装Node.js24+后：`node ui-prototype-v2/preview-server.mjs`，打开 http://127.0.0.1:5180 。完整的现成静态dist已包含。修改源码后在该目录执行 `pnpm install --frozen-lockfile` 和 `pnpm build`。演示不连接硬件、不采集声音。

## Android原生界面

JDK17、Gradle8.11.1、Android SDK35/Build Tools35.0.0。设置JAVA_HOME和ANDROID_HOME，运行 `gradle -p android-recorder :app:assembleDebug`。默认可使用原生界面、标记和声音样本；不含硬件实现。Release签名由维护者自行配置，仓库没有签名口令。

## 保留的扩展接口

- `RecorderAdapter`：设备初始化、权限、扫描、状态和已解码PCM回调。实现者私下接入获授权的依赖；公开接口不含设备命令、特征UUID、数据包布局或厂商类名。通过BuildConfig.RECORDER_ADAPTER_CLASS选择私有实现。
- `AudioSink`：连续PCM分块、队列、重试和结束语义，完整保留。字幕按session_id与utterance_id关联，原话与情绪不会因迟到事件串句。
- `RemoteCaptionFeed / AnalysisClient / ReplyPanel`：字幕、分析、快速/语气候选及用户点选TTS。
- `device_authorization_provider`：服务器加载由操作者私下安装的Provisioner(config,path)，调用get(installation_id)返回不透明JSON。项目接口 `/v1/sdk-auth` 和激活结果中的sdk_auth字段保留；公开代码不解释厂商授权结构，也不包含签发端点。
- `official-asr/client.py`：保留历史模块路径作为兼容接口，实际是通用HTTPS适配器。操作者配置asr.adapter_url，实现WAV请求→JSON `{text}`；可通过SPEECH_ADAPTER_TOKEN环境变量提供私有访问令牌。

云端读写、分析、激活及回应接口见 `docs/EXTENSION_POINTS.md`。这些均为项目自研HTTP接口，不是硬件通讯协议。生产提供商、硬件和凭证由操作者在仓库外提供；公开版本不宣称无需配置即可具备真实硬件/云模型能力。

前台使用；未提供声纹克隆、家人身份识别、锁屏持续守护或全机型保证。不同于带私有适配器的部署版，公开版不打包和分发专有组件。

## 1.0.3 更新与历史版本

本次只同步免 App 激活码注册、扫描全部兼容设备的接口语义，以及双版本下载页。详见 CHANGELOG.md。SDK 授权仍由私有提供器按其实际授权返回；本仓库不修改或承诺厂商授权期限。

公开版本默认提供本地界面与演示。连接自己的云端时，请配置 BuildConfig.SERVICE_ORIGIN 和私有服务配置；若采用新版免激活码客户端，服务器必须显式设置 allow_code_free_enrollment=true。首次云端音频处理确认保留。硬件功能由另行获授权的私有适配器提供。

原始公开版本的提交为 `23d23f78f59d8bc4a36730166de1405eda5068d7`。本次采用正常追加提交，保留历史；`v1.0.2-public` 对应原始公开版本，`v1.0.3-public` 对应本次更新。ZIP 是对应提交的源代码快照；GitHub 保留完整可回溯历史。

公开文件及源码包均不含生产 APK，不把带专有组件的上线安装包混入开源交付。网页中的两条下载路径由部署者提供有权分发的安装包，仓库和 ZIP 中没有这些文件。
