package com.enterprise.knowledge;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DocumentSecurity {
    private final String host; private final int port,timeoutMs;
    public DocumentSecurity(@Value("${app.clamd.host:}") String host,@Value("${app.clamd.port:3310}") int port,@Value("${app.clamd.timeout-ms:15000}") int timeoutMs) {
        this.host=host;this.port=port;this.timeoutMs=timeoutMs;
    }
    public void validate(Path path,String name) throws Exception {
        String lower=name.toLowerCase(Locale.ROOT);
        byte[] head;try(var in=Files.newInputStream(path)){head=in.readNBytes(8);}
        if(lower.endsWith(".pdf")) {
            if(!startsWith(head,"%PDF-"))throw new IllegalArgumentException("PDF 文件格式不合法");
        } else if(lower.endsWith(".docx")) {
            if(!startsWith(head,"PK"))throw new IllegalArgumentException("DOCX 文件格式不合法");
            try(var zip=new ZipFile(path.toFile())) {
                if(zip.getEntry("[Content_Types].xml")==null || zip.getEntry("word/document.xml")==null)throw new IllegalArgumentException("文件不是有效的 Word 文档");
            } catch(java.util.zip.ZipException e){throw new IllegalArgumentException("DOCX 文件格式不合法");}
        } else if(lower.endsWith(".txt")||lower.endsWith(".md")) {
            if(startsWith(head,"%PDF")||startsWith(head,"PK")||startsWith(head,"MZ"))throw new IllegalArgumentException("文件内容与文本扩展名不符");
            try {
                String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
                if(text.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'&&c!='\f'))throw new IllegalArgumentException("文本包含二进制控制字符");
            } catch(CharacterCodingException e){throw new IllegalArgumentException("文本文件必须使用 UTF-8 编码");}
        } else throw new IllegalArgumentException("不支持的文件类型");
    }
    public void scan(Path path) throws Exception {
        if(host==null||host.isBlank())return;
        try(var socket=new Socket()) {
            socket.connect(new InetSocketAddress(host,port),timeoutMs);socket.setSoTimeout(timeoutMs);
            var out=new DataOutputStream(socket.getOutputStream());out.write("nINSTREAM\n".getBytes(StandardCharsets.US_ASCII));
            try(var in=Files.newInputStream(path)) {byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){out.writeInt(n);out.write(buffer,0,n);}}
            out.writeInt(0);out.flush();
            var reply=new ByteArrayOutputStream();var in=socket.getInputStream();int b;
            while((b=in.read())!=-1&&b!=0&&b!='\n') {if(reply.size()>=1024)throw new IOException("病毒扫描响应异常");reply.write(b);}
            if(!reply.toString(StandardCharsets.US_ASCII).strip().equals("stream: OK"))throw new IllegalArgumentException("文件未通过病毒扫描，请联系管理员");
        } catch(SocketTimeoutException e){throw new IOException("病毒扫描超时，文件未放行",e);}
    }
    public void check(Path path,String name) throws Exception {validate(path,name);scan(path);}
    private static boolean startsWith(byte[] bytes,String prefix) {
        byte[] expected=prefix.getBytes(StandardCharsets.US_ASCII);if(bytes.length<expected.length)return false;
        for(int i=0;i<expected.length;i++)if(bytes[i]!=expected[i])return false;return true;
    }
}
