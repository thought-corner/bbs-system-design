package com.project.msa.view;

import org.springframework.stereotype.Service;

@Service
public class ViewService {

    private final ArticleViewCountRedisRepository articleViewCountRedisRepository;
    private final ViewCountBackupService viewCountBackupService;
    private final ViewProperties viewProperties;

    ViewService(ArticleViewCountRedisRepository articleViewCountRedisRepository,
                ViewCountBackupService viewCountBackupService, ViewProperties viewProperties) {
        this.articleViewCountRedisRepository = articleViewCountRedisRepository;
        this.viewCountBackupService = viewCountBackupService;
        this.viewProperties = viewProperties;
    }

    /**
     * 어뷰징 키를 잡은 요청만 조회수를 올린다 (D10). 못 잡으면 올리지 않고 현재 조회수를 돌려준다.
     * 백업 간격의 배수가 되면 그 게시글만 MySQL에 백업한다 (D9).
     */
    public long increase(long articleId, long userId) {
        if (!articleViewCountRedisRepository.acquireAbuseLock(articleId, userId, viewProperties.abuseLockTtl())) {
            return count(articleId);
        }
        if (articleViewCountRedisRepository.findViewCount(articleId).isEmpty()) {
            articleViewCountRedisRepository.fillIfAbsent(articleId, viewCountBackupService.findBackupViewCount(articleId));
        }
        long increasedViewCount = articleViewCountRedisRepository.increase(articleId);
        if (increasedViewCount % viewProperties.backupInterval() == 0) {
            viewCountBackupService.backup(articleId, increasedViewCount);
        }
        return increasedViewCount;
    }

    /** Redis 키가 사라졌으면 백업값을 돌려준다. */
    public long count(long articleId) {
        return articleViewCountRedisRepository.findViewCount(articleId)
                .orElseGet(() -> viewCountBackupService.findBackupViewCount(articleId));
    }
}
