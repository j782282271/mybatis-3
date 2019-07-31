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
package org.apache.ibatis.mapping;

import org.apache.ibatis.session.Configuration;

import java.util.Collections;
import java.util.Map;

/**
 * <discriminator javaType="int" column="draft">
 * ***<case value="1" resultMap="BaseResultMap"/>
 * </discriminator>
 *
 * @author Clinton Begin
 */
public class Discriminator {

    /**
     * Discriminator标签本身就是一种ResultMapping，所以包装ResultMapping
     */
    private ResultMapping resultMapping;
    /**
     * key：case 中的value
     * value：case 中的resultMapId
     */
    private Map<String, String> discriminatorMap;

    Discriminator() {
    }

    public static class Builder {
        private Discriminator discriminator = new Discriminator();

        public Builder(Configuration configuration, ResultMapping resultMapping, Map<String, String> discriminatorMap) {
            discriminator.resultMapping = resultMapping;
            discriminator.discriminatorMap = discriminatorMap;
        }

        public Discriminator build() {
            assert discriminator.resultMapping != null;
            assert discriminator.discriminatorMap != null;
            assert !discriminator.discriminatorMap.isEmpty();
            //lock down map
            discriminator.discriminatorMap = Collections.unmodifiableMap(discriminator.discriminatorMap);
            return discriminator;
        }
    }

    public ResultMapping getResultMapping() {
        return resultMapping;
    }

    public Map<String, String> getDiscriminatorMap() {
        return discriminatorMap;
    }

    public String getMapIdFor(String s) {
        return discriminatorMap.get(s);
    }

}
