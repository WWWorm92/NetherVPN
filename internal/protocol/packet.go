package protocol

import (
 "encoding/binary"
 "errors"
)

const (
 DataVersion uint8 = 1
 PacketTypeData uint8 = 3
 HeaderSize = 20
 AuthTagSize = 16
)

var (
 ErrShortPacket = errors.New("protocol: packet too short")
 ErrPacketVersion = errors.New("protocol: unsupported packet version")
 ErrPacketType = errors.New("protocol: unsupported packet type")
)

// Header is a draft, unauthenticated data-header representation.
// Values must not be trusted until authenticated by a future AEAD layer.
type Header struct {
 ConnectionID uint64
 KeyEpoch uint16
 Counter uint64
}

func (h Header) MarshalBinary() []byte {
 out := make([]byte, HeaderSize)
 out[0] = DataVersion
 out[1] = PacketTypeData
 binary.BigEndian.PutUint64(out[2:10], h.ConnectionID)
 binary.BigEndian.PutUint16(out[10:12], h.KeyEpoch)
 binary.BigEndian.PutUint64(out[12:20], h.Counter)
 return out
}

// ParseHeader validates a framed data packet minimum length, version and type.
// This function does NOT authenticate any bytes.
func ParseHeader(packet []byte) (Header, error) {
 if len(packet) < HeaderSize+AuthTagSize {
  return Header{}, ErrShortPacket
 }
 if packet[0] != DataVersion {
  return Header{}, ErrPacketVersion
 }
 if packet[1] != PacketTypeData {
  return Header{}, ErrPacketType
 }
 return Header{
  ConnectionID: binary.BigEndian.Uint64(packet[2:10]),
  KeyEpoch: binary.BigEndian.Uint16(packet[10:12]),
  Counter: binary.BigEndian.Uint64(packet[12:20]),
 }, nil
}
