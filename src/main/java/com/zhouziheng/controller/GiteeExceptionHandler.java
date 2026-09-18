package com.zhouziheng.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

/**
 * 把 Gitee 的报错翻译成一句能直接显示给用户的话。
 * <p>
 * 最需要翻译的是 403：匿名调用每小时只有 60 次，超了就全是 403，
 * 原样抛给前端只会显示成"请求失败：500"，看不出是限流，会让人以为是代码坏了。
 * 404 则大概率不是"写错了"：Gitee 的仓库级私人令牌只能访问它勾选过的仓库，
 * 作用域外的仓库一律按不存在返回，见 docs/notes/Gitee-接口限制.md。
 */
@RestControllerAdvice
public class GiteeExceptionHandler {

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<String> handle(RestClientResponseException e) {
        if (e.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("Gitee 接口限流了：匿名请求每小时只有 60 次，而一次评审要花掉好几次。"
                            + "等一小时、或者换个网络（换出口 IP）再试");
        }
        if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Gitee 上没找到这个仓库或 commit。如果不是自己账号名下的仓库，"
                            + "多半是当前令牌属于「仓库级私人令牌」，它只能访问勾选过的仓库；"
                            + "换成账号级令牌，或先把仓库 fork 到自己账号再试");
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("调用 Gitee 失败：" + e.getStatusCode());
    }
}
