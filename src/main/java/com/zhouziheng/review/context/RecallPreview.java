package com.zhouziheng.review.context;

import java.util.List;

/**
 * 召回预览的响应。
 * <p>
 * 召回链路只读文件、不调用模型，秒级返回且不产生费用，所以单独暴露一个预览接口：
 * 调整参数后先看召回结果，确认合适了再跑完整评审，不必每次都等一分钟。
 *
 * @param candidateFiles 候选文件数（符号解析出来的、理论上有资格被召回的）
 * @param files          实际召回的文件
 * @param contextChars   召回段总字符数（真正进提示词的）
 * @param rawChars       这些文件原始字符数合计
 * @param budget         本次用的字符预算
 */
public record RecallPreview(int candidateFiles,
                            List<RecalledFile> files,
                            int contextChars,
                            int rawChars,
                            int budget,
                            String signature,
                            long elapsedMillis) {

    public int percent() {
        return rawChars == 0 ? 0 : contextChars * 100 / rawChars;
    }
}
