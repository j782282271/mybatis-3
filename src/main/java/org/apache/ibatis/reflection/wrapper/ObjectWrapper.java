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
package org.apache.ibatis.reflection.wrapper;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.util.List;

/**
 * @author Clinton Begin
 */
public interface ObjectWrapper {

    /**
     * 根据PropertyTokenizer获取Object指定属性位置的值
     */
    Object get(PropertyTokenizer prop);

    /**
     * 根据PropertyTokenizer设置Object指定属性位置的值
     */
    void set(PropertyTokenizer prop, Object value);

    /**
     * 查找name属性值
     *
     * @param useCamelCaseMapping 是否忽略属性中的下划线
     */
    String findProperty(String name, boolean useCamelCaseMapping);

    /**
     * 所有getter名称集合，从class就可以获得，不需要object
     */
    String[] getGetterNames();

    /**
     * 所有setter名称集合，从class就可以获得，不需要object
     */
    String[] getSetterNames();

    /**
     * 所有setter的类型，从class就可以获得，不需要object
     */
    Class<?> getSetterType(String name);

    /**
     * 所有getter的类型，从class就可以获得，不需要object
     */
    Class<?> getGetterType(String name);

    boolean hasSetter(String name);

    boolean hasGetter(String name);

    /**
     * 为指定属性创建MetaObject
     */
    MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory);

    /**
     * 是否是集合Object
     */
    boolean isCollection();

    /**
     * 集合的add方法
     */
    void add(Object element);

    /**
     * 集合的addAll方法
     */
    <E> void addAll(List<E> element);

}
