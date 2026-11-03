package clientsettings
import ("os";"path/filepath";"testing")
func TestModeAndDNS(t *testing.T){
 for _,tc:=range []struct{src string;full bool}{
  {"[Client]\nMode = full\nDNS = 1.1.1.1\nAutoConnect = false\n",true},
  {"[Client]\nMode = split\nDNS = 9.9.9.9\n",false},
 }{
  s,err:=Parse(tc.src)
  if err!=nil||s.FullTunnel!=tc.full{t.Fatalf("%+v %v",s,err)}
 }
 for _,src:=range []string{"[Client]\nMode = yes\n","[Client]\nMode = full\nDNS = hostname\n","[Peer]\nMode = full\n","[Client]\nMode = split\nMode = full\n"} {
  if _,err:=Parse(src);err==nil{t.Errorf("accepted %q",src)}
 }
 dir:=t.TempDir()
 _,ok,err:=LoadIfExists(filepath.Join(dir,"missing"))
 if ok||err!=nil{t.Fatal("missing settings must be optional")}
 path:=filepath.Join(dir,"settings")
 if err=os.WriteFile(path,[]byte("[Client]\nMode = full\n"),0600);err!=nil{t.Fatal(err)}
 s,ok,err:=LoadIfExists(path)
 if !ok||err!=nil||!s.FullTunnel{t.Fatalf("%+v %v %v",s,ok,err)}
}
