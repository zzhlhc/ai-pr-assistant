package com.zhouziheng.controller;

import com.zhouziheng.review.ReviewOptions;
import com.zhouziheng.review.ReviewService;
import com.zhouziheng.review.context.RecallPreview;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 召回预览：只跑上下文召回，不调用模型。
 * <p>
 * 召回链路是纯读取加字符串处理，秒级返回且不产生费用，所以可以拿来试参数：
 * 调整"最多召回几个文件""字符预算"之后先看召回结果，确认合适了再跑完整评审，
 * 不必每次都等上一分钟、每次都花钱。
 */
@RestController
@RequestMapping("/api/context")
public class ContextPreviewController {

    private final ReviewService reviewService;

    public ContextPreviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/preview")
    public RecallPreview preview(@RequestBody PreviewRequest request) {
        return reviewService.preview(request.repo(), request.commitSha(), request.optionsOrDefault());
    }

    public record PreviewRequest(String repo, String commitSha, ReviewOptions options) {

        public ReviewOptions optionsOrDefault() {
            return options == null ? ReviewOptions.DEFAULT : options;
        }
    }
}
