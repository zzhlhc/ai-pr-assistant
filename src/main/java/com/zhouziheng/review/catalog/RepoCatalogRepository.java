package com.zhouziheng.review.catalog;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 预置仓库清单的读取，现在是**兜底**用。
 * <p>
 * 正常路径是实时拉令牌能访问的仓库（{@code GiteeClient#listMyRepos}）。
 * 这份清单是历史产物：当初 Gitee 的 /search/repositories 对任何请求都返回 total_count 0
 * （实测匿名、带 token、换 IP、换关键字都一样，服务端固定卡 10 秒），只能把项目名提前落库。
 * 现在留着当兜底——拉不到清单时页面至少还能选。想加项目直接 insert 这张表即可。
 */
@Repository
public class RepoCatalogRepository {

    private final JdbcClient jdbc;

    public RepoCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 全量返回，按 star 降序。
     * <p>
     * 一共 200 行、几十 KB，一次给前端比每次输入都打一次接口更划算：
     * 下拉框的过滤在浏览器里做，零延迟，也不消耗任何 Gitee 配额。
     */
    public List<RepoOption> findAll() {
        return jdbc.sql("""
                        SELECT full_name, name, description, stars
                          FROM review_repo_catalog
                         ORDER BY stars DESC, full_name
                        """)
                .query((rs, rowNum) -> new RepoOption(
                        rs.getString("full_name"),
                        rs.getString("full_name"),
                        rs.getString("description"),
                        rs.getInt("stars"),
                        null))
                .list();
    }
}
