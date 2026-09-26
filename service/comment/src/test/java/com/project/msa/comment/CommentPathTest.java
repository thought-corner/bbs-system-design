package com.project.msa.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommentPathTest {

    @Test
    @DisplayName("Base62 문자는 0-9 < A-Z < a-z 순서로 올라가고 0000z 다음 형제는 00010이다")
    void siblingPathCarriesInBase62Order() {
        CommentPath articleRoot = CommentPath.articleRoot();

        assertThat(articleRoot.nextChildPath("00009").value()).isEqualTo("0000A");
        assertThat(articleRoot.nextChildPath("0000Z").value()).isEqualTo("0000a");
        assertThat(articleRoot.nextChildPath("0000z").value()).isEqualTo("00010");
        assertThat("0000A").isGreaterThan("00009");
        assertThat("0000a").isGreaterThan("0000Z");
    }

    @Test
    @DisplayName("형제가 zzzzz까지 찼으면 다음 경로를 만들 수 없다")
    void rejectsSiblingOverflow() {
        CommentPath parent = CommentPath.of("00000");

        assertThatThrownBy(() -> parent.nextChildPath("00000zzzzz"))
                .isInstanceOf(CommentPathLimitExceededException.class);
    }

    @Test
    @DisplayName("다음 자식 경로는 가장 큰 자손 경로에서 부모 바로 아래 5자만 잘라 1 더한다")
    void nextChildPathUsesDirectChildChunkOfLastDescendant() {
        CommentPath parent = CommentPath.of("00000");

        assertThat(parent.nextChildPath(null).value()).isEqualTo("0000000000");
        assertThat(parent.nextChildPath("00000000010000000003").value()).isEqualTo("0000000002");
    }

    @Test
    @DisplayName("depth 600인 경로 아래에는 자식 경로를 만들 수 없다")
    void rejectsChildBelowMaxDepth() {
        CommentPath deepest = CommentPath.of("00000".repeat(CommentPath.MAX_DEPTH));

        assertThatThrownBy(() -> deepest.nextChildPath(null))
                .isInstanceOf(CommentPathLimitExceededException.class);
    }
}
