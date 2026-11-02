package protocol

import (
 "errors"
 "testing"
)

func TestHeaderRoundTrip(t *testing.T) {
 original := Header{ConnectionID: 0x0102030405060708, KeyEpoch: 9, Counter: 12345}
 packet := append(original.MarshalBinary(), make([]byte, AuthTagSize)...)
 decoded, err := ParseHeader(packet)
 if err != nil { t.Fatal(err) }
 if decoded != original { t.Fatalf("got %+v, want %+v", decoded, original) }
 if packet[0] != 1 || packet[1] != 3 { t.Fatalf("unexpected frame prefix %x", packet[:2]) }
}

func TestHeaderRejectsMalformedPackets(t *testing.T) {
 good := append(Header{}.MarshalBinary(), make([]byte, AuthTagSize)...)
 cases := []struct{name string; data []byte; want error}{
  {"nil",nil,ErrShortPacket},
  {"no tag",good[:HeaderSize],ErrShortPacket},
  {"short tag",good[:len(good)-1],ErrShortPacket},
  {"version",append([]byte(nil),good...),ErrPacketVersion},
  {"type",append([]byte(nil),good...),ErrPacketType},
 }
 cases[3].data[0] = 2
 cases[4].data[1] = 4
 for _, tc := range cases {
  t.Run(tc.name,func(t *testing.T){
   _,err := ParseHeader(tc.data)
   if !errors.Is(err,tc.want) { t.Fatalf("got %v, want %v",err,tc.want) }
  })
 }
}
