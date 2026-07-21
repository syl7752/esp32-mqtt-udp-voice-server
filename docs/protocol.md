# MQTT 与 UDP 协议

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

