package com.project.msa.hotarticle;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/hot-articles")
class HotArticleController {

    private final HotArticleService hotArticleService;

    HotArticleController(HotArticleService hotArticleService) {
        this.hotArticleService = hotArticleService;
    }

    @GetMapping
    List<HotArticleResponse> read(@RequestParam @DateTimeFormat(pattern = "yyyyMMdd") Optional<LocalDate> date) {
        return hotArticleService.readConfirmed(date);
    }
}
