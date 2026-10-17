# 自研接入接口

## 设备

实现android-recorder中的RecorderAdapter。initialize接收Activity、不透明授权JSON和Listener；设备连接/录音状态经onState通知；onPcmStart给出项目会话ID、采样率和声道；onPcm提供PCM16LE及采集时间；onPcmEnd结束流。蓝牙操作和所需权限完全由私有适配器负责。公开仓库不约定任何设备命令、特征UUID、包结构或厂商SDK调用。

## 服务器

`POST /v1/enroll` 接收installation_id、installation_secret、activation_code，返回分角色访问配置及不透明sdk_auth扩展对象。`POST /v1/audio/chunks`、`GET /v1/transcripts`、`GET /v1/transcripts/cursor`、`GET /v1/analysis`、`POST /v1/replies`、`POST /v1/reply-interest` 保留项目自研合同。`GET /v1/sdk-auth`仅转发私有授权提供器的结果；没有默认厂商行为。

敏感配置必须存放在仓库外。网关需要public_origin、token_secret、activation_sha256、asr、omni、reply；令牌由部署者随机生成，不使用文档中的固定值。asr.adapter_url为部署者的HTTPS适配器。omni及reply由部署者提供自己获授权的模型配置。不要提交真实配置。输入音频/个人记录默认不属于示例数据。

注册私有设备授权提供器：在服务配置device_authorization_provider中填写Python模块名；模块导出Provisioner(config,path)，其get(identity)返回JSON对象。不透明授权由部署者和私有设备适配器协商，不在本仓库公开。
