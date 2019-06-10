/**
 * Copyright 2009-2015 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.parsing;

/**
 * 普通记号解析器，处理#{}和${}参数
 *
 * @author Clinton Begin
 */
public class GenericTokenParser {

    private final String openToken;
    private final String closeToken;
    private final TokenHandler handler;

    public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
        this.openToken = openToken;
        this.closeToken = closeToken;
        this.handler = handler;
    }

    public String parse(String text) {
        final StringBuilder builder = new StringBuilder();
        final StringBuilder expression = new StringBuilder();
        if (text != null && text.length() > 0) {
            char[] src = text.toCharArray();
            int offset = 0;
            //#{favouriteSection,jdbcType=VARCHAR}
            //这里是循环解析参数，参考GenericTokenParserTest,比如可以解析${first_name} ${initial} ${last_name} reporting.这样的字符串,里面有3个 ${}
            int start = text.indexOf(openToken, offset);
            while (start > -1) {
                //判断一下 ${ 前面是否是反斜杠，这个逻辑在老版的mybatis中（如3.1.0）是没有的
                if (start > 0 && src[start - 1] == '\\') {
                    // this open token is escaped. remove the backslash and continue.
                    builder.append(src, offset, start - offset - 1).append(openToken);
                    offset = start + openToken.length();
                } else {
                    // found open token. let's search close token.
                    expression.setLength(0);
                    builder.append(src, offset, start - offset);
                    offset = start + openToken.length();
                    int end = text.indexOf(closeToken, offset);
                    while (end > -1) {
                        if (end > offset && src[end - 1] == '\\') {
                            // this close token is escaped. remove the backslash and continue.
                            expression.append(src, offset, end - offset - 1).append(closeToken);
                            offset = end + closeToken.length();
                            end = text.indexOf(closeToken, offset);
                        } else {
                            expression.append(src, offset, end - offset);
                            offset = end + closeToken.length();
                            break;
                        }
                    }
                    if (end == -1) {
                        // close token was not found.
                        builder.append(src, start, src.length - start);
                        offset = src.length;
                    } else {
                        builder.append(handler.handleToken(expression.toString()));
                        offset = end + closeToken.length();
                    }
                }
                start = text.indexOf(openToken, offset);
            }
            if (offset < src.length) {
                builder.append(src, offset, src.length - offset);
            }
        }
        return builder.toString();
    }
}
