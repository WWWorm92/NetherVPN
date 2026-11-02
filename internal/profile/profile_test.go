package profile
import "testing"
func TestParse(t *testing.T){
 data:="[Interface]\nPrivateKey = 012345\nAddress = 10.77.0.2/30\nMTU = 1280\n[Peer]\nPublicKey = abc\nEndpoint = 127.0.0.1:51830\nAllowedIPs = 10.77.0.1\n"
 p,e:=Parse(data);if e!=nil{t.Fatal(e)}
 if p.MTU!=1280||p.Endpoint!="127.0.0.1:51830"{t.Fatalf("%+v",p)}
}
func TestInvalid(t *testing.T){if _,e:=Parse("[Peer]\nBad=1");e==nil{t.Fatal("expected error")}}
