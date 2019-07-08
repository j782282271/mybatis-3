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
package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.Properties;

/**
 * 输入node如下：
 * <include refid="baseSql"/>
 * ***<property name="name" value="val"/>
 * <include/>
 * 找到sql，并递归替换其中的${}，然后替换node为替换后的sql
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

    private final Configuration configuration;
    private final MapperBuilderAssistant builderAssistant;

    public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
        this.configuration = configuration;
        this.builderAssistant = builderAssistant;
    }

    /**
     * 输入node如下：
     * <include refid="baseSql"/>
     * ***<property name="name" value="val"/>
     * <include/>
     * 引用baseSql，将baseSql中的${name}替换为val,baseSql中的#{a}并不替换
     */
    public void applyIncludes(Node source) {
        Properties variablesContext = new Properties();
        Properties configurationVariables = configuration.getVariables();
        if (configurationVariables != null) {
            variablesContext.putAll(configurationVariables);
        }
        applyIncludes(source, variablesContext);
    }

    /**
     * Recursively apply includes through all SQL fragments.
     * <include refid="baseSql"/>
     * ***<property name="name" value="val"/>
     * <include/>
     * 引用baseSql，将baseSql中的${name}替换为val,baseSql中的#{a}并不替换
     * 递归
     *
     * @param source           Include node in DOM tree
     * @param variablesContext Current context for static variables with values
     */
    private void applyIncludes(Node source, final Properties variablesContext) {
        if (source.getNodeName().equals("include")) {
            // new full context for included SQL - contains inherited context and new variables from current include node
            Properties fullContext;

            String refid = getStringAttribute(source, "refid");
            //替换refid中的 ${}
            refid = PropertyParser.parse(refid, variablesContext);
            //找到sql片段，该sql片段中可能还包含其他sql片段，所以需要递归
            Node toInclude = findSqlFragment(refid);
            //找到source中定义的属性对
            Properties newVariablesContext = getVariablesContext(source, variablesContext);
            if (!newVariablesContext.isEmpty()) {
                // merge contexts
                fullContext = new Properties();
                fullContext.putAll(variablesContext);
                fullContext.putAll(newVariablesContext);
            } else {
                // no new context - use inherited fully
                fullContext = variablesContext;
            }
            //替换sql中的${}
            applyIncludes(toInclude, fullContext);
            if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
                //source所在doc引入toInclude
                toInclude = source.getOwnerDocument().importNode(toInclude, true);
            }
            //替换source为toInclude
            source.getParentNode().replaceChild(toInclude, source);
            while (toInclude.hasChildNodes()) {
                //toInclude的子node放在toInclude前
                toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
            }
            //删除toInclude
            toInclude.getParentNode().removeChild(toInclude);
        } else if (source.getNodeType() == Node.ELEMENT_NODE) {
            //对每个子node处理
            NodeList children = source.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                applyIncludes(children.item(i), variablesContext);
            }
        } else if (source.getNodeType() == Node.ATTRIBUTE_NODE && !variablesContext.isEmpty()) {
            // replace variables in all attribute values
            source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
        } else if (source.getNodeType() == Node.TEXT_NODE && !variablesContext.isEmpty()) {
            // replace variables ins all text nodes
            //如果当前节点为test节点，将nodeValue即test内容${}中的元素进行替换
            source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
        }
    }

    /**
     * 根据refid找到对应的sql片段node
     */
    private Node findSqlFragment(String refid) {
        refid = builderAssistant.applyCurrentNamespace(refid, true);
        try {
            XNode nodeToInclude = configuration.getSqlFragments().get(refid);
            return nodeToInclude.getNode().cloneNode(true);
        } catch (IllegalArgumentException e) {
            throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
        }
    }

    private String getStringAttribute(Node node, String name) {
        return node.getAttributes().getNamedItem(name).getNodeValue();
    }

    /**
     * Read placholders and their values from include node definition.
     * <include refid="baseSql"/>
     * ***<property name="name" value="val"/>
     * <include/>
     * 引用baseSql，将baseSql中的${name}替换为val,baseSql中的#{a}并不替换
     * 本方法为找到所有<property>的name-value对，放到Properties中并返回
     *
     * @param node                      Include node instance
     * @param inheritedVariablesContext Current context used for replace variables in new variables values
     * @return variables context from include instance (no inherited values)
     */
    private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
        Properties variablesContext = new Properties();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                String name = getStringAttribute(n, "name");
                String value = getStringAttribute(n, "value");
                // Replace variables inside
                value = PropertyParser.parse(value, inheritedVariablesContext);
                // Push new value
                Object originalValue = variablesContext.put(name, value);
                if (originalValue != null) {
                    throw new BuilderException("Variable " + name + " defined twice in the same include definition");
                }
            }
        }
        return variablesContext;
    }

}
