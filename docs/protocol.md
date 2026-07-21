# MQTT 与 UDP 协议

## OTA 配置接口

设备启动时调用：

```http
POST /mqtt/api/device/ota
Device-Id: 94:a9:90:31:c4:04
Content-Type: application/json
```

响应示例：

```json
{
  "mqtt": {
    "endpoint": "192.168.1.10:1883",
    "client_id": "esp32-voice-94_a9_90_31_c4_04",
    "username": "",
    "password": "",
    "keepalive": 60,
    "publish_topic": "devices/esp32-voice-94_a9_90_31_c4_04/up",
    "subscribe_topic": "devices/esp32-voice-94_a9_90_31_c4_04/down"
  },
  "server_time": {
    "timestamp": 1784271600000,
    "timezone": "UTC",
    "timezone_offset": 0
  },
  "firmware": {
    "version": "",
    "url": ""
  }
}
```

此接口不创建 UDP 会话。设备使用返回值连接 MQTT 并发送下面的 `hello` 后，服务端才创建会话并下发 UDP 地址。

示例部署通过 EMQX Auto Subscribe 将每个新连接自动订阅到 `devices/${clientid}/down`。这是未主动订阅下行主题的固件接收控制消息所必需的 Broker 配置。

## MQTT 控制消息

### Hello

设备发送：

```json
{"type":"hello","version":1,"transport":"udp"}
```

服务返回：

```json
{
  "type":"hello",
  "transport":"udp",
  "session_id":"32-character-session-id",
  "audio_params":{
    "format":"opus",
    "sample_rate":24000,
    "channels":1,
    "frame_duration":60
  },
  "udp":{
    "server":"192.168.1.10",
    "port":8888,
    "key":"32-character-AES-128-key-in-hex",
    "nonce":"32-character-base-nonce-in-hex"
  }
}
```

### Listen

```json
{"type":"listen","session_id":"...","state":"start","mode":"auto"}
```

### Abort

```json
{"type":"abort","session_id":"..."}
```

### Goodbye

```json
{"type":"goodbye","session_id":"..."}
```

### 服务状态事件

```json
{"type":"stt","text":"识别文本"}
{"type":"tts","state":"start"}
{"type":"tts","state":"stop"}
```

## UDP 音频包

每个包由 16 字节 nonce 和 AES-CTR 加密后的单个 Opus 帧组成：

```text
offset  size  field
0       1     type (0x01)
1       1     flags
2       2     encrypted payload length, big-endian
4       4     random stream id, fixed during the session
8       4     timestamp, big-endian
12      4     sequence, big-endian
16      N     AES-128-CTR encrypted Opus frame
```

整个 16 字节头同时作为 AES-CTR IV。服务使用字节 `4..7` 查找 Redis 中的会话。

音频参数固定为 24 kHz、单声道、60 ms Opus 帧。服务端解码后重采样为 16 kHz PCM 供 Silero VAD 和 FunASR 使用，TTS 输出会转换回 24 kHz 并重新编码为 Opus。
