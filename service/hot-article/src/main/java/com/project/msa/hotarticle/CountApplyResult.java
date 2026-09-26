package com.project.msa.hotarticle;

/** `apply-count.lua`의 반환값. 지표에서 늦게 와서 버린 이벤트만 따로 센다. */
enum CountApplyResult {

    APPLIED, NOT_CANDIDATE, STALE;

    static CountApplyResult of(Long scriptResult) {
        if (scriptResult == null || scriptResult == 0L) {
            return NOT_CANDIDATE;
        }
        return scriptResult > 0 ? APPLIED : STALE;
    }
}
