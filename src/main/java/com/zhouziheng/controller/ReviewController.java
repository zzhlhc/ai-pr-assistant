package com.zhouziheng.controller;

import com.zhouziheng.review.ReviewService;
import com.zhouziheng.review.model.ReviewReport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public ReviewReport review(@RequestBody ReviewRequest request) {
        return reviewService.review(request.repo(), request.commitSha()).report();
    }

    public record ReviewRequest(String repo, String commitSha) {
    }
}
