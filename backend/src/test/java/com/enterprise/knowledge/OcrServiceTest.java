package com.enterprise.knowledge;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class OcrServiceTest {
    @TempDir Path temp;
    private OcrService service(boolean all,String command){return new OcrService(new ObjectMapper(),true,all,20,3,command,"tesseract","chi_sim+eng");}
    @Test void recognizesEmptyAndSparsePagesAndSupportsForcedOcr() {
        var ocr=service(false,"");assertThat(ocr.needed("")).isTrue();assertThat(ocr.needed("扫描页标题")).isTrue();
        assertThat(ocr.needed("一".repeat(30))).isFalse();assertThat(service(true,"").needed("一".repeat(30))).isTrue();
    }
    @Test void preservesQuotedWindowsPathsAndRejectsMalformedArguments() throws Exception {
        var ocr=service(false,"");assertThat(ocr.arguments("\"C:\\OCR Tools\\engine.exe\" \"{file}\" {page}"))
            .containsExactly("C:\\OCR Tools\\engine.exe","{file}","{page}");
        assertThatThrownBy(()->ocr.arguments("\"unterminated")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ocr.arguments("[7]")).isInstanceOf(IllegalArgumentException.class);
    }
    private List<String> helper(String mode){return List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),Helper.class.getName(),mode);}
    @Test void passesFileAndPageAsSeparateArgumentsWithoutMixingStderr() throws Exception {
        var args=new ArrayList<>(helper("echo"));args.add("{file}");args.add("{page}");
        Path pdf=temp.resolve("中文 document.pdf");try(var document=new PDDocument()) {
            document.addPage(new PDPage());document.save(pdf.toFile());
            var ocr=service(false,new ObjectMapper().writeValueAsString(args));
            assertThat(ocr.recognize(pdf,document,1)).isEqualTo(pdf.toAbsolutePath()+"|1");
        }
    }
    @Test void terminatesHungExternalCommand() {assertThatThrownBy(()->OcrService.execute(helper("sleep"),1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("超时");}
    public static class Helper {
        public static void main(String[] args) throws Exception {
            if(args[0].equals("sleep")){Thread.sleep(30000);return;}
            System.err.print("diagnostic only");System.out.write((args[1]+"|"+args[2]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
