package com.enterprise.knowledge;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DocumentSecurityTest {
    @TempDir Path temp;
    @Test void rejectsRenamedPdfBinaryTextAndGenericZip() throws Exception {
        var security=new DocumentSecurity("",3310,1000);Path file=temp.resolve("sample");
        Files.writeString(file,"%PDF-1.7");assertThatThrownBy(()->security.validate(file,"a.txt")).isInstanceOf(IllegalArgumentException.class);
        Files.write(file,new byte[]{0,1,2});assertThatThrownBy(()->security.validate(file,"a.md")).isInstanceOf(IllegalArgumentException.class);
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))){zip.putNextEntry(new ZipEntry("test.txt"));zip.write(1);zip.closeEntry();}
        assertThatThrownBy(()->security.validate(file,"a.docx")).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(file,"中文文档\n正常内容");security.validate(file,"a.md");
    }
    private void scanWithReply(String reply,boolean accepted) throws Exception {
        Path file=temp.resolve("input.txt");byte[] data=new byte[20000];java.util.Arrays.fill(data,(byte)'a');Files.write(file,data);
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var executor=Executors.newSingleThreadExecutor();
            try {
                var peer=executor.submit(()->{
                    try(var socket=server.accept()) {
                        socket.setSoTimeout(2000);var in=new DataInputStream(socket.getInputStream());
                        assertThat(new String(in.readNBytes(10),StandardCharsets.US_ASCII)).isEqualTo("nINSTREAM\n");
                        int total=0,n;while((n=in.readInt())!=0){assertThat(n).isBetween(1,8192);total+=in.readNBytes(n).length;}
                        assertThat(total).isEqualTo(data.length);socket.getOutputStream().write((reply+"\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
                    }
                    return null;
                });
                var security=new DocumentSecurity(InetAddress.getLoopbackAddress().getHostAddress(),server.getLocalPort(),1000);
                if(accepted)security.scan(file);else assertThatThrownBy(()->security.scan(file)).isInstanceOf(IllegalArgumentException.class);
                peer.get(3,TimeUnit.SECONDS);
            } finally {executor.shutdownNow();}
        }
    }
    @Test void streamsChunksAndAcceptsOnlyExplicitCleanResult() throws Exception {scanWithReply("stream: OK",true);}
    @Test void rejectsInfectedAndMisleadingResponses() throws Exception {scanWithReply("stream: Eicar-Signature FOUND",false);scanWithReply("stream: NOT OK",false);}
    @Test void scannerTimeoutDoesNotReleaseFile() throws Exception {
        Path file=temp.resolve("input.txt");Files.writeString(file,"sample");
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var security=new DocumentSecurity(InetAddress.getLoopbackAddress().getHostAddress(),server.getLocalPort(),100);
            assertThatThrownBy(()->security.scan(file)).isInstanceOf(IOException.class).hasMessageContaining("超时");
        }
    }
}
