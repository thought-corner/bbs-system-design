package com.project.msa.hotarticle;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class HotArticleService {

    private final HotArticleRedisRepository hotArticleRedisRepository;
    private final HotArticleProperties properties;
    private final Clock clock;

    HotArticleService(HotArticleRedisRepository hotArticleRedisRepository, HotArticleProperties properties,
                      Clock clock) {
        this.hotArticleRedisRepository = hotArticleRedisRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 ${hot-article.confirm-hour} * * *", zone = "${hot-article.zone}")
    void confirmYesterday() {
        confirm(LocalDate.now(clock).minusDays(1));
    }

    /** 같은 랭킹을 같은 목록으로 복사할 뿐이라 여러 인스턴스가 동시에 돌아도 락이 필요 없다 (D14). */
    public void confirm(LocalDate createdDate) {
        List<HotArticleResponse> hotArticles = hotArticleRedisRepository.findTopArticleIds(createdDate).stream()
                .flatMap(articleId -> hotArticleRedisRepository.findCreated(articleId).stream()
                        .map(createdDay -> new HotArticleResponse(articleId, createdDay.boardId())))
                .toList();
        hotArticleRedisRepository.saveConfirmedList(createdDate, hotArticles);
    }

    /** 날짜를 주지 않으면 가장 최근 확정일: 확정 시각 전이면 전전날, 이후면 전날 (D14). */
    public List<HotArticleResponse> readConfirmed(Optional<LocalDate> createdDate) {
        return hotArticleRedisRepository.findConfirmedList(createdDate.orElseGet(this::latestConfirmedDate));
    }

    private LocalDate latestConfirmedDate() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate yesterday = now.toLocalDate().minusDays(1);
        return now.getHour() < properties.confirmHour() ? yesterday.minusDays(1) : yesterday;
    }
}
