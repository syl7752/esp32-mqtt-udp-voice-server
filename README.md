# ESP32 MQTT + UDP Voice Server

一个不包含业务系统的最小语音链路：ESP32 通过 MQTT 完成会话控制，通过 AES-CTR 加密的 UDP 传输 Opus 音频。

```text
ESP32 --MQTT--> mqtt-server --Redis--> udp-server
ESP32 <--MQTT-- mqtt-server <--Redis-- udp-server
ESP32 <====== AES-CTR + Opus UDP =====> udp-server
                                      ├─ Silero VAD
                                      ├─ FunASR
                                      ├─ OpenAI Responses API
                                      └─ Index-TTS-vLLM
```

仓库只有两个 Java 服务：

- `mqtt-server`：处理 `hello`、`listen`、`abort`、`goodbye`，创建 UDP 会话并转发设备状态。
- `udp-server`：接收和发送加密 Opus 音频，执行 VAD → ASR → LLM → TTS 流水线。

## 环境要求

- Java 17+
- Maven 3.9+
- Redis
- MQTT Broker（示例使用 EMQX）
- 用户自行部署的 Silero VAD、FunASR 和 Index-TTS-vLLM
- OpenAI API Key

## 外部语音服务

本仓库不分发模型、权重、参考音频或第三方服务源码。

1. 从 [Silero VAD](https://github.com/snakers4/silero-vad) 获取 ONNX 模型，放到 `models/silero_vad.onnx`，或设置 `SILERO_VAD_MODEL`。
2. 按 [FunASR](https://github.com/modelscope/FunASR) 文档自行部署服务。本项目调用其 Whisper 兼容接口 `POST /v1/audio/transcriptions`。
3. 按 [Index-TTS-vLLM](https://github.com/Ksuriuri/index-tts-vllm) 文档自行部署并注册 voice。本项目调用 `POST /audio/speech`。

使用任何模型前，请自行阅读并接受该模型对应的许可证。Index-TTS 代码许可证与模型许可证并不相同。

## 启动

启动 Redis 和 EMQX：

```bash
docker compose up -d
```

准备配置：

```bash
cp .env.example .env
set -a
source .env
set +a
```

构建：

```bash
mvn -DskipTests package
```

分别启动两个服务：

```bash
java -jar mqtt-server/target/mqtt-server-0.1.0-SNAPSHOT.jar
java -jar udp-server/target/udp-server-0.1.0-SNAPSHOT.jar
```

`UDP_PUBLIC_HOST` 必须是 ESP32 能访问的 UDP 服务地址，不能保留默认的 `127.0.0.1`。

## MQTT 主题

默认主题：

- 上行：`devices/{clientId}/up`
- 下行：`devices/{clientId}/down`

服务也兼容 EMQX 规则包装格式：

```json
{"clientid":"device-001","payload":{"type":"hello","transport":"udp"}}
```

直接 MQTT payload：

```json
{"type":"hello","transport":"udp"}
```

详细消息和 UDP 包格式见 [docs/protocol.md](docs/protocol.md)。

## 安全说明

- 不要提交 `.env`、日志、模型、参考音频或生成音频。
- MQTT 和 Redis 在公网部署时必须启用认证和网络访问控制。
- 参考音频可能属于个人声纹数据，使用前必须获得授权。
- 服务不会记录 AES key、nonce、完整 MQTT payload、用户转写文本或 TTS 文本。

## License

本仓库代码采用 Apache-2.0。第三方软件和模型适用各自许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

