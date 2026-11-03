package tunnel

import (
 "bytes"
 "context"
 "errors"
 "io"
 "net"
 "net/netip"
 "testing"
 "time"
)

// packetStream models individual TUN reads, not a byte stream. Each Read
// delivers one datagram even if previous reads were malformed or oversized.
type packetStream struct { packets [][]byte; index int }
func (r *packetStream) Read(dst []byte)(int,error){
 if r.index>=len(r.packets){return 0,io.EOF}
 p:=r.packets[r.index]
 r.index++
 return copy(dst,p),nil
}

func TestFilteredPumpKeepsSessionAndDropsUnauthorizedPackets(t *testing.T) {
 sender,receiver:=pair(t)
 bind:=&net.UDPAddr{IP:net.IPv4(127,0,0,1)}
 out,err:=net.ListenUDP("udp4",bind);if err!=nil{t.Fatal(err)}
 defer out.Close()
 in,err:=net.ListenUDP("udp4",bind);if err!=nil{t.Fatal(err)}
 defer in.Close()

 malformed:=[]byte{0x45,0x00,0x00}
 ipv6:=make([]byte,40)
 ipv6[0]=0x60
 spoofed:=ipv4()
 spoofed[15]=0x03
 allowed:=ipv4()
 oversized:=make([]byte,DefaultMTU+1)
 oversized[0]=0x45
 input:=&packetStream{packets:[][]byte{malformed,ipv6,spoofed,oversized,allowed}}
 var rejected []error

 policy:=func(packet []byte)error{
  if packet[0]>>4!=4{return ErrInvalidPacket}
  return VerifySource(packet,netip.MustParseAddr("10.77.0.2"))
 }
 result:=PumpToUDPFiltered(context.Background(),input,out,in.LocalAddr().(*net.UDPAddr),sender,DefaultMTU,policy,
  func(reason error,n int){rejected=append(rejected,reason)})
 if !errors.Is(result,io.EOF){t.Fatalf("filtered pump quit incorrectly: %v",result)}
 if len(rejected)!=4{t.Fatalf("expected four drops, got %d: %v",len(rejected),rejected)}
 if !errors.Is(rejected[2],ErrUnauthorizedIP){t.Fatalf("spoofed IP not rejected: %v",rejected[2])}
 if !errors.Is(rejected[3],ErrOversizedPacket){t.Fatalf("oversized packet not dropped: %v",rejected[3])}

 if err:=in.SetReadDeadline(time.Now().Add(time.Second));err!=nil{t.Fatal(err)}
 buf:=make([]byte,DefaultMTU+128)
 n,_,err:=in.ReadFromUDP(buf)
 if err!=nil{t.Fatalf("legitimate IPv4 packet was not forwarded: %v",err)}
 decoded,err:=receiver.Open(buf[:n])
 if err!=nil{t.Fatalf("bad encrypted frame: %v",err)}
 if !bytes.Equal(decoded,allowed){t.Fatalf("wrong forwarded packet: %x",decoded)}
 // No rejected datagram must ever have been transmitted.
 if err:=in.SetReadDeadline(time.Now().Add(50*time.Millisecond));err!=nil{t.Fatal(err)}
 if n,_,err:=in.ReadFromUDP(buf);err==nil{t.Fatalf("forwarded a rejected packet (%d bytes)",n)}else if ne,ok:=err.(net.Error);!ok||!ne.Timeout(){t.Fatalf("unexpected socket error: %v",err)}
}

func TestStrictPumpStillReportsInvalidPacket(t *testing.T){
 sender,_:=pair(t)
 out,err:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.IPv4(127,0,0,1)})
 if err!=nil{t.Fatal(err)}
 defer out.Close()
 bad:=&packetStream{packets:[][]byte{{0x45}}}
 err=PumpToUDP(context.Background(),bad,out,out.LocalAddr().(*net.UDPAddr),sender,DefaultMTU,
  func([]byte)error{return nil})
 if !errors.Is(err,ErrInvalidPacket){t.Fatalf("strict API semantics changed: %v",err)}
}

func TestFilteredPumpOnlyDropsPolicyErrors(t *testing.T){
 sender,_:=pair(t)
 out,err:=net.ListenUDP("udp4",&net.UDPAddr{IP:net.IPv4(127,0,0,1)})
 if err!=nil{t.Fatal(err)}
 defer out.Close()
 ctx,cancel:=context.WithCancel(context.Background())
 cancel()
 err=PumpToUDPFiltered(ctx,&packetStream{packets:[][]byte{ipv4()}},out,out.LocalAddr().(*net.UDPAddr),sender,DefaultMTU,
  func([]byte)error{return nil},nil)
 if !errors.Is(err,context.Canceled){t.Fatalf("canceled session did not terminate: %v",err)}
 if err=PumpToUDPFiltered(context.Background(),&packetStream{},nil,out.LocalAddr().(*net.UDPAddr),sender,DefaultMTU,
  func([]byte)error{return nil},nil);!errors.Is(err,ErrMissingPeer){
  t.Fatalf("missing transport not detected: %v",err)
 }
}
