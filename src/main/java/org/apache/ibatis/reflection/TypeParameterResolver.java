/**
 * Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.reflection;

import java.lang.reflect.*;

/**
 * 该类的作用：
 * 类1： interface Level0Mapper<L, M, N> {
 * **** Map<N, M> selectMap();
 * **** }
 * 类2： interface Level1Mapper<E, F> extends Level0Mapper<E, F, String> {
 * }
 * 类3：public interface Level2Mapper extends Level1Mapper<Date, Integer>{
 * <p>
 * }
 * 下面方法返回结果为java.util.Map<N, M>，即不含有具体的参数类型，只能通过Level1Mapper的继承结构中含有的参数具体信息来解析
 * Type type = Level2Mapper.class.getMethod("selectMap").getGenericReturnType();
 * 解析流程大概如下:
 * 判断返回type是否是class，是class，则说明不含有未知信息(N,M这种)，如果不是则说明含有未知信息(N,M这种)，这就需要从继承链路中找到匹配类型了
 * 于是，从该selectMap方法的declaringClass中找N,M。然后Level2Mapper（srcType）向上的继承链路中找到N,M对应的真实类型，这两个类型再加上RowType:Map就得到了返回值
 * 即：declaringClass中存储了N,M这种参数占位符，Level2Mapper的superClass链路中存储了N,M对应的真实类型
 *
 * @author Iwao AVE!
 */
public class TypeParameterResolver {

    /**
     * @return The field type as {@link Type}. If it has type parameters in the declaration,<br>
     * they will be resolved to the actual runtime {@link Type}s.
     */
    public static Type resolveFieldType(Field field, Type srcType) {
        Type fieldType = field.getGenericType();
        Class<?> declaringClass = field.getDeclaringClass();
        return resolveType(fieldType, srcType, declaringClass);
    }

    /**
     * @return The return type of the method as {@link Type}. If it has type parameters in the declaration,<br>
     * they will be resolved to the actual runtime {@link Type}s.
     */
    public static Type resolveReturnType(Method method, Type srcType) {
        Type returnType = method.getGenericReturnType();
        Class<?> declaringClass = method.getDeclaringClass();
        return resolveType(returnType, srcType, declaringClass);
    }

    /**
     * @return The parameter types of the method as an array of {@link Type}s. If they have type parameters in the declaration,<br>
     * they will be resolved to the actual runtime {@link Type}s.
     */
    public static Type[] resolveParamTypes(Method method, Type srcType) {
        Type[] paramTypes = method.getGenericParameterTypes();
        Class<?> declaringClass = method.getDeclaringClass();
        Type[] result = new Type[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            result[i] = resolveType(paramTypes[i], srcType, declaringClass);
        }
        return result;
    }

    private static Type resolveType(Type type, Type srcType, Class<?> declaringClass) {
        if (type instanceof TypeVariable) {
            //例如：T
            return resolveTypeVar((TypeVariable<?>) type, srcType, declaringClass);
        } else if (type instanceof ParameterizedType) {
            //例如：List<T>
            return resolveParameterizedType((ParameterizedType) type, srcType, declaringClass);
        } else if (type instanceof GenericArrayType) {
            //例如：T[]或者List<T>[]
            return resolveGenericArrayType((GenericArrayType) type, srcType, declaringClass);
        } else {
            //例如String
            return type;
        }
    }

    private static Type resolveGenericArrayType(GenericArrayType genericArrayType, Type srcType, Class<?> declaringClass) {
        Type componentType = genericArrayType.getGenericComponentType();
        Type resolvedComponentType = null;
        if (componentType instanceof TypeVariable) {
            resolvedComponentType = resolveTypeVar((TypeVariable<?>) componentType, srcType, declaringClass);
        } else if (componentType instanceof GenericArrayType) {
            resolvedComponentType = resolveGenericArrayType((GenericArrayType) componentType, srcType, declaringClass);
        } else if (componentType instanceof ParameterizedType) {
            resolvedComponentType = resolveParameterizedType((ParameterizedType) componentType, srcType, declaringClass);
        }
        if (resolvedComponentType instanceof Class) {
//          T[]
            return Array.newInstance((Class<?>) resolvedComponentType, 0).getClass();
        } else {
//          List<T>[]
            return new GenericArrayTypeImpl(resolvedComponentType);
        }
    }

    private static ParameterizedType resolveParameterizedType(ParameterizedType parameterizedType, Type srcType, Class<?> declaringClass) {
        Class<?> rawType = (Class<?>) parameterizedType.getRawType();
        Type[] typeArgs = parameterizedType.getActualTypeArguments();
        Type[] args = new Type[typeArgs.length];
        for (int i = 0; i < typeArgs.length; i++) {
            if (typeArgs[i] instanceof TypeVariable) {
                args[i] = resolveTypeVar((TypeVariable<?>) typeArgs[i], srcType, declaringClass);
            } else if (typeArgs[i] instanceof ParameterizedType) {
                args[i] = resolveParameterizedType((ParameterizedType) typeArgs[i], srcType, declaringClass);
            } else if (typeArgs[i] instanceof WildcardType) {
                //WildcardType只能存在于parameterizedType中，如List<? extend T>,不会直接出现在字段或者返回值中
                args[i] = resolveWildcardType((WildcardType) typeArgs[i], srcType, declaringClass);
            } else {
                args[i] = typeArgs[i];
            }
        }
        return new ParameterizedTypeImpl(rawType, null, args);
    }

    /**
     * wildcardType如? extends N
     */
    private static Type resolveWildcardType(WildcardType wildcardType, Type srcType, Class<?> declaringClass) {
        Type[] lowerBounds = resolveWildcardTypeBounds(wildcardType.getLowerBounds(), srcType, declaringClass);
        Type[] upperBounds = resolveWildcardTypeBounds(wildcardType.getUpperBounds(), srcType, declaringClass);
        return new WildcardTypeImpl(lowerBounds, upperBounds);
    }

    private static Type[] resolveWildcardTypeBounds(Type[] bounds, Type srcType, Class<?> declaringClass) {
        Type[] result = new Type[bounds.length];
        for (int i = 0; i < bounds.length; i++) {
            if (bounds[i] instanceof TypeVariable) {
                result[i] = resolveTypeVar((TypeVariable<?>) bounds[i], srcType, declaringClass);
            } else if (bounds[i] instanceof ParameterizedType) {
                result[i] = resolveParameterizedType((ParameterizedType) bounds[i], srcType, declaringClass);
            } else if (bounds[i] instanceof WildcardType) {
                result[i] = resolveWildcardType((WildcardType) bounds[i], srcType, declaringClass);
            } else {
                result[i] = bounds[i];
            }
        }
        return result;
    }

    /**
     * 可以先看看TypeParameterResolverTest.myTest方法介绍：关于ParameterizedType中：getRawType.getTypeParameters与getActualTypeArguments之间的对应关系
     *
     * @param typeVar        typeVar中含有未知类型如Map<N,M>中，Map已经确认，N,M待确认
     * @param srcType        srcType中含有N,M的真实类型的位置j，第一次进来这个方法时，该字段为class，当递归调用的时候，会传如Type类型，所以此处为Type类型而不是class类型
     * @param declaringClass declaringClass中含有N,M所在参数变量位置i，i=j的位置j即为N,M的真实类型位置(srcType中)
     */
    private static Type resolveTypeVar(TypeVariable<?> typeVar, Type srcType, Class<?> declaringClass) {
        Type result = null;
        Class<?> clazz = null;
        //clazz为srcType的rawType，即不含有参数类型信息的class
        if (srcType instanceof Class) {
            clazz = (Class<?>) srcType;
        } else if (srcType instanceof ParameterizedType) {
            //注意parameterizedType是含有具体参数类型信息的，如Level1Mapper<Date, Integer>（见Level2Mapper定义处：extends Level1Mapper<Date, Integer>）
            ParameterizedType parameterizedType = (ParameterizedType) srcType;
            //clazz是不含有具体参数类型信息的，但是含有参数占位符如Level1Mapper<E, F>，可以parameterizedType中记录的Level1Mapper<Date, Integer>对应上（见Level1Mapper定义处）
            clazz = (Class<?>) parameterizedType.getRawType();
        } else {
            throw new IllegalArgumentException("The 2nd arg must be Class or ParameterizedType, but was: " + srcType.getClass());
        }

        if (clazz == declaringClass) {
            return Object.class;
        }

        Type superclass = clazz.getGenericSuperclass();
        result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superclass);
        if (result != null) {
            return result;
        }

        Type[] superInterfaces = clazz.getGenericInterfaces();
        for (Type superInterface : superInterfaces) {
            result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superInterface);
            if (result != null) {
                return result;
            }
        }
        return Object.class;
    }

    /**
     * 以单元测试testField_GenericField方法为例，第一次进来
     *
     * @param typeVar=T，实际类型为TypeVariableImpl
     * @param srcType=Calculator$SubCalculator,实际类型为class
     * @param declaringClass=Calculator,实际类型为class，含有真实类型的占位符如N,M
     * @param clazz=Calculator$SubCalculator，实际类型为class
     * @param superclass=Calculator<java.lang.String>，实际类型为ParameterizedType，含有真实类型如Date,String
     */
    private static Type scanSuperTypes(TypeVariable<?> typeVar, Type srcType, Class<?> declaringClass, Class<?> clazz, Type superclass) {
        Type result = null;
        if (superclass instanceof ParameterizedType) {
            //parentAsType=Calculator<java.lang.String>
            ParameterizedType parentAsType = (ParameterizedType) superclass;
            //parentAsClass=Calculator
            Class<?> parentAsClass = (Class<?>) parentAsType.getRawType();
            //declaringClass=Calculator
            if (declaringClass == parentAsClass) {
                //typeArgs={String} class
                Type[] typeArgs = parentAsType.getActualTypeArguments();
                //declaredTypeVars={T}
                TypeVariable<?>[] declaredTypeVars = declaringClass.getTypeParameters();
                for (int i = 0; i < declaredTypeVars.length; i++) {
                    //declaredTypeVars[0]=T,typeVar=T，此处逻辑为，从typeVar的声明类中找到typeVar
                    if (declaredTypeVars[i] == typeVar) {
                        //typeArgs[0]=String class类型
                        if (typeArgs[i] instanceof TypeVariable) {
                            //本分支以单元测试testReturn_LV2Map为例，Level2Mapper.selectMap方法返回值解析
                            //第二次进到本函数中时typeArgs[i]=F，declaredTypeVars[i]=typeVar=M，srcType=Level1Mapper<Date, Integer>
                            //declaringClass=Level0Mapper<L, M, N> ,clazz=Level1Mapper<E, F>，superclass=Level0Mapper<E, F, java.lang.String>
                            //需要从clazz的F位置定位srcType对应位置为什么，为Integer
                            //此处图形化解释：
                            //interface Level2Mapper extends Level1Mapper<Date, Integer>
                            //                                    ↑srcType
                            //interface Level1Mapper<E, F> extends Level0Mapper<E, F, String>
                            //      ↑clazz（typeParams={E,F}）         ↑superclass（也是parentAsType，typeArgs={E,F,String}）
                            //interface Level0Mapper<L, M, N> { Map<N, M> selectMap(); }
                            //              ↑declaringClass           ↑typeVar=M
                            //看上图，此处分支之上的逻辑是这样的：遍历declaringClass，的getTypeParameters，找到和typeVar=M相同的TypeVariable为M，这个M在声明函数中，需要到superclass对应位置i找到F
                            //发现F为TypeVariable，于是进入到本分支，根据superclass中的F,确定clazz中的F的位置j，srcType中的getActualTypeArguments该位置就是实际类型
                            //以上过程为M(typeVar)->M(declaringClass)->F(superclass)->F(clazz)->Integer(srcType)
                            TypeVariable<?>[] typeParams = clazz.getTypeParameters();
                            for (int j = 0; j < typeParams.length; j++) {
                                if (typeParams[j] == typeArgs[i]) {
                                    if (srcType instanceof ParameterizedType) {
                                        result = ((ParameterizedType) srcType).getActualTypeArguments()[j];
                                    }
                                    break;
                                }
                            }
                        } else {
                            result = typeArgs[i];
                        }
                    }
                }
            } else if (declaringClass.isAssignableFrom(parentAsClass)) {
                //declaringClass != parentAsClass，说明declaringClass还再上更面
                //srcType参数中需要含有真实类型信息，所以parentAsType它作为srcType才能保证真实类型信息继续传递下去
                //parentAsType就是superClass
                result = resolveTypeVar(typeVar, parentAsType, declaringClass);
            }
        } else if (superclass instanceof Class) {
            //理论上不会走到这里，因为下面解释：
            //ClassA<String> extends ClassB extends ClassC<T>的时候
            // srcType=ClassA<String>，superclass=ClassB才会走到这里，不会存在这种继承结构的
            if (declaringClass.isAssignableFrom((Class<?>) superclass)) {
                result = resolveTypeVar(typeVar, superclass, declaringClass);
            }
        }
        return result;
    }

    private TypeParameterResolver() {
        super();
    }

    static class ParameterizedTypeImpl implements ParameterizedType {
        private Class<?> rawType;

        private Type ownerType;

        private Type[] actualTypeArguments;

        public ParameterizedTypeImpl(Class<?> rawType, Type ownerType, Type[] actualTypeArguments) {
            super();
            this.rawType = rawType;
            this.ownerType = ownerType;
            this.actualTypeArguments = actualTypeArguments;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public Type getRawType() {
            return rawType;
        }
    }

    static class WildcardTypeImpl implements WildcardType {
        private Type[] lowerBounds;

        private Type[] upperBounds;

        private WildcardTypeImpl(Type[] lowerBounds, Type[] upperBounds) {
            super();
            this.lowerBounds = lowerBounds;
            this.upperBounds = upperBounds;
        }

        @Override
        public Type[] getLowerBounds() {
            return lowerBounds;
        }

        @Override
        public Type[] getUpperBounds() {
            return upperBounds;
        }
    }

    static class GenericArrayTypeImpl implements GenericArrayType {
        private Type genericComponentType;

        private GenericArrayTypeImpl(Type genericComponentType) {
            super();
            this.genericComponentType = genericComponentType;
        }

        @Override
        public Type getGenericComponentType() {
            return genericComponentType;
        }
    }
}
