package com.project.msa.comment;

/**
 * depth마다 Base62 5자를 이어 붙인 댓글 경로 (D6).
 * 문자 순서 `0-9 < A-Z < a-z`는 `ascii_bin` 바이트 순서와 같아서 `ORDER BY path`가 곧 트리 순서다.
 */
final class CommentPath {

    static final String CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final int DEPTH_CHUNK_SIZE = 5;
    /** `path` 컬럼 3000자 / 5자. */
    static final int MAX_DEPTH = 600;

    private static final long SIBLING_CAPACITY = pow(CHARSET.length(), DEPTH_CHUNK_SIZE);
    private static final String FIRST_CHUNK = "0".repeat(DEPTH_CHUNK_SIZE);

    private final String path;

    private CommentPath(String path) {
        this.path = path;
    }

    /** 최상위 댓글들의 부모 자리. 빈 경로다. */
    static CommentPath articleRoot() {
        return new CommentPath("");
    }

    static CommentPath of(String path) {
        if (path.length() % DEPTH_CHUNK_SIZE != 0) {
            throw new IllegalArgumentException("comment path length must be a multiple of " + DEPTH_CHUNK_SIZE + ": " + path);
        }
        return new CommentPath(path);
    }

    String value() {
        return path;
    }

    int depth() {
        return path.length() / DEPTH_CHUNK_SIZE;
    }

    boolean isArticleRoot() {
        return path.isEmpty();
    }

    CommentPath parentPath() {
        return new CommentPath(path.substring(0, path.length() - DEPTH_CHUNK_SIZE));
    }

    /**
     * 이 경로 아래 가장 큰 자손 경로에서 이 경로 바로 아래 5자를 잘라 1 더한 것이 다음 자식 경로다.
     *
     * @param lastDescendantPath 이 경로 아래 가장 큰 자손 경로. 자손이 없으면 null
     */
    CommentPath nextChildPath(String lastDescendantPath) {
        if (depth() + 1 > MAX_DEPTH) {
            throw new CommentPathLimitExceededException("comment depth cannot exceed " + MAX_DEPTH);
        }
        if (lastDescendantPath == null) {
            return new CommentPath(path + FIRST_CHUNK);
        }
        String lastChildChunk = lastDescendantPath.substring(path.length(), path.length() + DEPTH_CHUNK_SIZE);
        return new CommentPath(path + increase(lastChildChunk));
    }

    private static String increase(String chunk) {
        long nextSibling = decode(chunk) + 1;
        if (nextSibling >= SIBLING_CAPACITY) {
            throw new CommentPathLimitExceededException("no more sibling path after " + chunk);
        }
        return encode(nextSibling);
    }

    private static long decode(String chunk) {
        long value = 0;
        for (char digit : chunk.toCharArray()) {
            value = value * CHARSET.length() + CHARSET.indexOf(digit);
        }
        return value;
    }

    private static String encode(long value) {
        char[] chunk = new char[DEPTH_CHUNK_SIZE];
        for (int i = DEPTH_CHUNK_SIZE - 1; i >= 0; i--) {
            chunk[i] = CHARSET.charAt((int) (value % CHARSET.length()));
            value /= CHARSET.length();
        }
        return new String(chunk);
    }

    private static long pow(int base, int exponent) {
        long result = 1;
        for (int i = 0; i < exponent; i++) {
            result *= base;
        }
        return result;
    }
}
