package com.comet.opik.domain.template;

import com.github.mustachejava.Code;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheException;
import com.github.mustachejava.MustacheFactory;
import com.github.mustachejava.codes.ValueCode;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Singleton
public class MustacheParser implements TemplateParser {

    /**
     * 按原样渲染值：每个消费者都会把内容喂给 LLM，而对替换后的 trace 输入进行转义会向评判模型隐藏其 JSON 结构
     * （OPIK-7354）。转义还会弄乱 {@code =} 和反引号，而不仅仅是引号。这与前端预览以及从不转义的
     * {@link PythonTemplateParser} 保持一致。
     */
    private static final MustacheFactory MF = new DefaultMustacheFactory() {
        @Override
        public void encode(String value, Writer writer) {
            try {
                writer.write(value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    @Override
    public Set<String> extractVariables(String template) {
        Set<String> variables = new HashSet<>();

        if (StringUtils.isBlank(template)) {
            return variables;
        }

        try {
            // 初始化 Mustache 工厂
            Mustache mustache = MF.compile(new StringReader(template), "template");

            // 获取模板的根节点
            Code[] codes = mustache.getCodes();
            collectVariables(codes, variables);

            return variables;
        } catch (MustacheException | IllegalArgumentException ex) {
            log.warn("解析 Mustache 模板以提取变量失败", ex);
            return variables; // 解析失败时返回空集
        }
    }

    @Override
    public String render(String template, Map<String, ?> context) {
        if (template == null) {
            return "";
        }

        try {
            Mustache mustache = MF.compile(new StringReader(template), "template");
            return renderTemplate(context, mustache);
        } catch (MustacheException ex) {
            log.error("解析 Mustache 模板以进行渲染失败:", ex);
            throw new IllegalArgumentException("Invalid Mustache template", ex);
        }
    }

    private String renderTemplate(Map<String, ?> context, Mustache mustache) {
        try (Writer writer = mustache.execute(new StringWriter(), context)) {
            writer.flush();
            return writer.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render template", e);
        }
    }

    private static void collectVariables(Code[] codes, Set<String> variables) {
        for (Code code : codes) {
            if (Objects.requireNonNull(code) instanceof ValueCode valueCode) {
                variables.add(valueCode.getName());
            } else {
                Optional.ofNullable(code)
                        .map(Code::getCodes)
                        .map(it -> it.length > 0)
                        .ifPresent(it -> collectVariables(code.getCodes(), variables));
            }
        }
    }

}
