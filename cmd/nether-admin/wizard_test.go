package main
import(
 "bufio"
 "bytes"
 "path/filepath"
 "fmt"
 "os"
 "strings"
 "testing"
 "github.com/WWWorm92/NetherVPN/internal/handshake"
)
func testConfig(t *testing.T)(string,string,string){
 t.Helper()
 dir:=t.TempDir()
 server,err:=handshake.GenerateIdentity();if err!=nil{t.Fatal(err)}
 client,err:=handshake.GenerateIdentity();if err!=nil{t.Fatal(err)}
 config:=filepath.Join(dir,"server.conf")
 data:=fmt.Sprintf("[Interface]\nName = nvpn0\nPrivateKey = %x\nAddress = 10.77.0.1/24\nListenPort = 51830\n\n[Peer]\nPublicKey = %x\nAllowedIPs = 10.77.0.2\n",server.Private,client.Public)
 if err=os.WriteFile(config,[]byte(data),0600);err!=nil{t.Fatal(err)}
 return config,filepath.Join(dir,"peers"),filepath.Join(dir,"clients")
}
func TestClientPrompt(t *testing.T){
 config,_,_:=testConfig(t)
 var output bytes.Buffer
 answer,err:=askClient(bufio.NewReader(strings.NewReader("laptop\n77.221.132.76:51830\nда\n")),&output,config,"","")
 if err!=nil{t.Fatal(err)}
 if answer.Name!="laptop"||answer.Endpoint!="77.221.132.76:51830"{t.Fatal(answer)}
 for _,bad:=range []string{"Тип устройства","DNS-сервер","Режим:"}{
  if strings.Contains(output.String(),bad){t.Fatalf("unexpected prompt %q",bad)}
 }
}
func TestInteractiveMenuInfoAndExit(t *testing.T){
 config,peers,clients:=testConfig(t)
 var out bytes.Buffer
 if err:=runMenu(bufio.NewReader(strings.NewReader("4\n2\n5\n")),&out,config,peers,clients);err!=nil{t.Fatal(err)}
 if !strings.Contains(out.String(),"Интерфейс")||strings.Contains(out.String(),"PrivateKey ="){t.Fatal(out.String())}
}
func TestCreateCancelled(t *testing.T){
 config,_,_:=testConfig(t)
 var out bytes.Buffer
 _,err:=askClient(bufio.NewReader(strings.NewReader("phone\n77.221.132.76:51830\nнет\n")),&out,config,"","")
 if err==nil{t.Fatal("cancel should abort")}
}
