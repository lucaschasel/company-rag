package com.enterprise.knowledge;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TextChunksTest {
    @Test void overlappingChunksKeepTail() {
        assertThat(TextChunks.split("abcdefghijk",5,2)).containsExactly("abcde","defgh","ghijk");
        assertThat(TextChunks.split(" \n ",5,1)).isEmpty();
        assertThatThrownBy(()->TextChunks.split("x",5,5)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void chineseTermsAndVectorMath() {
        assertThat(TextChunks.terms("代码审查 RAG")).contains("代码","码审","审查","rag");
        assertThat(TextChunks.cosine(new double[]{1,0},new double[]{0,1})).isZero();
        assertThat(TextChunks.cosine(new double[]{1,2},new double[]{1,2})).isCloseTo(1,org.assertj.core.data.Offset.offset(0.00001));
        assertThat(TextChunks.cosine(new double[]{0,0},new double[]{0,0})).isZero();
    }
}
