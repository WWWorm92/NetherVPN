package main

import(
 "bytes"
 "strings"
 "testing"
)

func TestWizardLinuxAndFullTunnel(t *testing.T){
 inputs:="dev_laptop\n2\n1\n9.9.9.9\nда\n"
 var output bytes.Buffer
 options,confirmed,err:=wizard(strings.NewReader(inputs),&output,"not-needed.conf",provisionOptions{Endpoint:"77.221.132.76:51830",DNS:"1.1.1.1"})
 if err!=nil{t.Fatal(err)}
 if !confirmed||options.Name!="dev_laptop"||options.Platform!="linux"||!options.FullTunnel||options.DNS!="9.9.9.9"{
  t.Fatalf("unexpected options: %+v",options)
 }
 if !strings.Contains(output.String(),"Публичный IPv4"){ // Provided explicitly, no endpoint question expected.
  if !strings.Contains(output.String(),"Linux") && !strings.Contains(output.String(),"linux"){t.Fatal("missing platform summary")}
 }
}

func TestWizardSplitAndCancel(t *testing.T){
 var output bytes.Buffer
 options,confirmed,err:=wizard(strings.NewReader("device\n1\n2\nда\n"),&output,"unused",provisionOptions{Endpoint:"77.221.132.76:51830"})
 if err!=nil||!confirmed||options.FullTunnel{t.Fatalf("split mode: %+v %v",options,err)}
 _,confirmed,err=wizard(strings.NewReader("device\n1\n1\n\nнет\n"),&output,"unused",provisionOptions{Endpoint:"77.221.132.76:51830"})
 if confirmed||err==nil{t.Fatal("cancel must not create profile")}
}

func TestWizardEOF(t *testing.T){
 var output bytes.Buffer
 _,confirmed,err:=wizard(strings.NewReader(""),&output,"unused",provisionOptions{Endpoint:"77.221.132.76:51830"})
 if confirmed||err==nil{t.Fatal("missing input must fail closed")}
}
