package com.project.msa.view;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param backupInterval 조회수가 이 수의 배수가 될 때마다 MySQL에 백업한다 (D9)
 * @param abuseLockTtl   같은 사용자의 같은 게시글 조회를 한 번으로 세는 창 (D10)
 */
@ConfigurationProperties("view")
public record ViewProperties(long backupInterval, Duration abuseLockTtl) {
}
