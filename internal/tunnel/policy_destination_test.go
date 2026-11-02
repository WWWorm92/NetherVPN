package tunnel

import ("net/netip";"testing")

func TestDestinationIPAndSourceSpoof(t *testing.T){
 packet:=make([]byte,20)
 packet[0]=0x45
 packet[2]=0
 packet[3]=20
 copy(packet[12:16],[]byte{1,1,1,1})
 copy(packet[16:20],[]byte{10,77,0,3})
 target,err:=DestinationIP(packet)
 if err!=nil||target!=netip.MustParseAddr("10.77.0.3"){t.Fatalf("destination %v %v",target,err)}
 if err:=VerifySource(packet,netip.MustParseAddr("10.77.0.3"));err==nil{
  t.Fatal("client source spoof accepted")
 }
 if _,err:=DestinationIP(packet[:19]);err==nil{t.Fatal("short IP accepted")}
}
