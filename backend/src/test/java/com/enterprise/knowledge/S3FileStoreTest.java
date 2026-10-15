package com.enterprise.knowledge;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class S3FileStoreTest {
    private static byte[] decodeAwsChunks(byte[] body) throws IOException {
        var in=new ByteArrayInputStream(body);var out=new ByteArrayOutputStream();
        while(true) {
            var header=new ByteArrayOutputStream();int b;
            while((b=in.read())!=-1&&b!='\n')if(b!='\r')header.write(b);
            int size=Integer.parseInt(header.toString(java.nio.charset.StandardCharsets.US_ASCII).split(";",2)[0],16);
            if(size==0)return out.toByteArray();
            out.write(in.readNBytes(size));assertThat(in.readNBytes(2)).isEqualTo(new byte[]{13,10});
        }
    }
    @Test void usesPathStyleAndCleansDownloadedTemporaryFile() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        var stored=new AtomicReference<byte[]>();var path=new AtomicReference<String>();
        server.createContext("/",exchange->{
            path.set(exchange.getRequestURI().getPath());
            switch(exchange.getRequestMethod()) {
                case "PUT" -> {byte[] body=exchange.getRequestBody().readAllBytes();
                    String encoding=exchange.getRequestHeaders().getFirst("x-amz-content-sha256");
                    if(encoding!=null&&encoding.startsWith("STREAMING-"))body=decodeAwsChunks(body);
                    stored.set(body);exchange.sendResponseHeaders(200,-1);}
                case "GET" -> {byte[] data=stored.get();exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);}
                case "DELETE" -> {stored.set(null);exchange.sendResponseHeaders(204,-1);}
            }
            exchange.close();
        });server.start();
        var store=new S3FileStore("knowledge","http://localhost:"+server.getAddress().getPort(),"us-east-1","test-key","test-secret",true);
        try {
            byte[] data="source document".getBytes(java.nio.charset.StandardCharsets.UTF_8);store.save("doc-1",new ByteArrayInputStream(data),data.length);
            assertThat(path.get()).isEqualTo("/knowledge/doc-1");
            Path local=store.toLocalFile("doc-1");assertThat(Files.readAllBytes(local)).isEqualTo(data);store.releaseLocal(local);assertThat(Files.exists(local)).isFalse();
            store.delete("doc-1");assertThat(stored.get()).isNull();
        } finally {store.close();server.stop(0);}
    }
}
