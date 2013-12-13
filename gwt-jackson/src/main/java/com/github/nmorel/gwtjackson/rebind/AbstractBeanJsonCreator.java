/*
 * Copyright 2013 Nicolas Morel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.nmorel.gwtjackson.rebind;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.nmorel.gwtjackson.client.ser.bean.AbstractBeanJsonSerializer;
import com.google.gwt.core.ext.GeneratorContext;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JField;
import com.google.gwt.core.ext.typeinfo.JMethod;
import com.google.gwt.core.ext.typeinfo.JParameter;
import com.google.gwt.core.ext.typeinfo.JPrimitiveType;
import com.google.gwt.core.ext.typeinfo.JType;
import com.google.gwt.user.rebind.SourceWriter;

/**
 * @author Nicolas Morel
 */
public abstract class AbstractBeanJsonCreator extends AbstractCreator {

    protected static class TypeParameters {

        private final List<String> typeParameterMapperNames;

        private final String joinedTypeParameterMappersWithType;

        private final String joinedTypeParameterMappersWithoutType;

        public TypeParameters( List<String> typeParameterMapperNames, String joinedTypeParameterMappersWithType,
                               String joinedTypeParameterMappersWithoutType ) {
            this.typeParameterMapperNames = typeParameterMapperNames;
            this.joinedTypeParameterMappersWithType = joinedTypeParameterMappersWithType;
            this.joinedTypeParameterMappersWithoutType = joinedTypeParameterMappersWithoutType;
        }

        public List<String> getTypeParameterMapperNames() {
            return typeParameterMapperNames;
        }

        public String getJoinedTypeParameterMappersWithType() {
            return joinedTypeParameterMappersWithType;
        }

        public String getJoinedTypeParameterMappersWithoutType() {
            return joinedTypeParameterMappersWithoutType;
        }
    }

    protected BeanJsonMapperInfo mapperInfo;

    public AbstractBeanJsonCreator( TreeLogger logger, GeneratorContext context, RebindConfiguration configuration,
                                    JacksonTypeOracle typeOracle ) {
        super( logger, context, configuration, typeOracle );
    }

    /**
     * Creates an implementation of {@link AbstractBeanJsonSerializer} for the type given in
     * parameter
     *
     * @param beanType type of the bean
     *
     * @return the fully qualified name of the created class
     * @throws com.google.gwt.core.ext.UnableToCompleteException
     */
    public BeanJsonMapperInfo create( JClassType beanType ) throws UnableToCompleteException {

        mapperInfo = typeOracle.getBeanJsonMapperInfo( beanType );

        String packageName = beanType.getPackage().getName();

        if ( null == mapperInfo ) {

            // we concatenate the name of all the enclosing class
            StringBuilder builder = new StringBuilder( beanType.getSimpleSourceName() );
            JClassType enclosingType = beanType.getEnclosingType();
            while ( null != enclosingType ) {
                builder.insert( 0, enclosingType.getSimpleSourceName() + "_" );
                enclosingType = enclosingType.getEnclosingType();
            }

            String simpleSerializerClassName = builder.toString() + "BeanJsonSerializerImpl";
            String qualifiedSerializerClassName = packageName + "." + simpleSerializerClassName;
            String simpleDeserializerClassName = builder.toString() + "BeanJsonDeserializerImpl";
            String qualifiedDeserializerClassName = packageName + "." + simpleDeserializerClassName;

            mapperInfo = new BeanJsonMapperInfo( beanType, qualifiedSerializerClassName, simpleSerializerClassName,
                qualifiedDeserializerClassName, simpleDeserializerClassName );

            // retrieve the informations on the beans and its properties
            BeanInfo info = BeanInfo.process( logger, typeOracle, mapperInfo );
            mapperInfo.setBeanInfo( info );

            Map<String, PropertyInfo> properties = findAllProperties( info );
            mapperInfo.setProperties( properties );

            typeOracle.addBeanJsonMapperInfo( beanType, mapperInfo );
        }

        PrintWriter printWriter = getPrintWriter( packageName, getSimpleClassName() );
        // the class already exists, no need to continue
        if ( printWriter == null ) {
            return mapperInfo;
        }

        String parameterizedTypes = beanType.getParameterizedQualifiedSourceName();

        SourceWriter source = getSourceWriter( printWriter, packageName, getSimpleClassName() + getGenericClassBoundedParameters(),
            getSuperclass() + "<" +
            parameterizedTypes + ">" );

        writeClassBody( source, mapperInfo.getBeanInfo(), mapperInfo.getProperties() );

        return mapperInfo;
    }

    protected abstract boolean isSerializer();

    protected String getSimpleClassName() {
        if ( isSerializer() ) {
            return mapperInfo.getSimpleSerializerClassName();
        } else {
            return mapperInfo.getSimpleDeserializerClassName();
        }
    }

    protected String getGenericClassBoundedParameters() {
        return mapperInfo.getGenericClassBoundedParameters();
    }

    protected String getSuperclass() {
        if ( isSerializer() ) {
            return ABSTRACT_BEAN_JSON_SERIALIZER_CLASS;
        } else {
            return ABSTRACT_BEAN_JSON_DESERIALIZER_CLASS;
        }
    }

    protected abstract void writeClassBody( SourceWriter source, BeanInfo info, Map<String,
        PropertyInfo> properties ) throws UnableToCompleteException;

    private Map<String, PropertyInfo> findAllProperties( BeanInfo info ) throws UnableToCompleteException {
        Map<String, PropertyInfo> result = new LinkedHashMap<String, PropertyInfo>();
        if ( null != info.getType().isInterface() ) {
            // no properties on interface
            return result;
        }

        Map<String, FieldAccessors> fieldsMap = new LinkedHashMap<String, FieldAccessors>();
        parseFields( info.getType(), fieldsMap );
        parseMethods( info.getType(), fieldsMap );

        // Processing all the properties accessible via field, getter or setter
        Map<String, PropertyInfo> propertiesMap = new LinkedHashMap<String, PropertyInfo>();
        for ( FieldAccessors field : fieldsMap.values() ) {
            PropertyInfo property = PropertyInfo.process( logger, typeOracle, field, mapperInfo );
            if ( !property.isVisible() ) {
                logger.log( TreeLogger.Type.DEBUG, "Field " + field.getFieldName() + " of type " + info.getType() + " is not visible" );
            } else {
                propertiesMap.put( property.getPropertyName(), property );
            }
        }

        // We look if there is any constructor parameters not found yet
        if ( !info.getCreatorParameters().isEmpty() ) {
            for ( Map.Entry<String, JParameter> entry : info.getCreatorParameters().entrySet() ) {
                PropertyInfo property = propertiesMap.get( entry.getKey() );
                if ( null == property ) {
                    propertiesMap.put( entry.getKey(), PropertyInfo.process( logger, typeOracle, entry.getKey(), entry.getValue(), info ) );
                } else if ( entry.getValue().getAnnotation( JsonProperty.class ).required() ) {
                    property.setRequired( true );
                }
            }
        }

        // we first add the properties defined in order
        for ( String orderedProperty : info.getPropertyOrderList() ) {
            // we remove the entry to have the map with only properties with natural or alphabetic order
            PropertyInfo property = propertiesMap.remove( orderedProperty );
            if ( null != property ) {
                result.put( property.getPropertyName(), property );
            }
        }

        // if the user asked for an alphabetic order, we sort the rest of the properties
        if ( info.isPropertyOrderAlphabetic() ) {
            List<Map.Entry<String, PropertyInfo>> entries = new ArrayList<Map.Entry<String, PropertyInfo>>( propertiesMap.entrySet() );
            Collections.sort( entries, new Comparator<Map.Entry<String, PropertyInfo>>() {
                public int compare( Map.Entry<String, PropertyInfo> a, Map.Entry<String, PropertyInfo> b ) {
                    return a.getKey().compareTo( b.getKey() );
                }
            } );
            for ( Map.Entry<String, PropertyInfo> entry : entries ) {
                result.put( entry.getKey(), entry.getValue() );
            }
        } else {
            for ( Map.Entry<String, PropertyInfo> entry : propertiesMap.entrySet() ) {
                result.put( entry.getKey(), entry.getValue() );
            }
        }

        findIdPropertyInfo( result, info.getIdentityInfo() );

        return result;
    }

    private void parseFields( JClassType type, Map<String, FieldAccessors> propertiesMap ) {
        if ( null == type || type.getQualifiedSourceName().equals( "java.lang.Object" ) ) {
            return;
        }

        for ( JField field : type.getFields() ) {
            if ( field.isStatic() ) {
                continue;
            }

            String fieldName = field.getName();
            FieldAccessors property = propertiesMap.get( fieldName );
            if ( null == property ) {
                property = new FieldAccessors( fieldName );
                propertiesMap.put( fieldName, property );
            }
            if ( null == property.getField() ) {
                property.setField( field );
            } else {
                // we found an other field with the same name on a superclass. we ignore it
                logger.log( TreeLogger.Type.WARN, "A field with the same name as " + field
                    .getName() + " has already been found on child class" );
            }
        }
        parseFields( type.getSuperclass(), propertiesMap );
    }

    private void parseMethods( JClassType type, Map<String, FieldAccessors> propertiesMap ) {
        if ( null == type || type.getQualifiedSourceName().equals( "java.lang.Object" ) ) {
            return;
        }

        for ( JMethod method : type.getMethods() ) {
            if ( null != method.isConstructor() || method.isStatic() ) {
                continue;
            }

            JType returnType = method.getReturnType();
            if ( null != returnType.isPrimitive() && JPrimitiveType.VOID.equals( returnType.isPrimitive() ) ) {
                // might be a setter
                if ( method.getParameters().length == 1 ) {
                    String methodName = method.getName();
                    if ( (methodName.startsWith( "set" ) && methodName.length() > 3) || method.isAnnotationPresent( JsonProperty.class ) ) {
                        // it's a setter method
                        String fieldName = extractFieldNameFromGetterSetterMethodName( methodName );
                        FieldAccessors property = propertiesMap.get( fieldName );
                        if ( null == property ) {
                            property = new FieldAccessors( fieldName );
                            propertiesMap.put( fieldName, property );
                        }
                        property.addSetter( method );
                    }
                }
            } else {
                // might be a getter
                if ( method.getParameters().length == 0 ) {
                    String methodName = method.getName();
                    if ( (methodName.startsWith( "get" ) && methodName.length() > 3) || (methodName.startsWith( "is" ) && methodName
                        .length() > 2 && null != returnType.isPrimitive() && JPrimitiveType.BOOLEAN.equals( returnType
                        .isPrimitive() )) || method.isAnnotationPresent( JsonProperty.class ) ) {
                        // it's a getter method
                        String fieldName = extractFieldNameFromGetterSetterMethodName( methodName );
                        FieldAccessors property = propertiesMap.get( fieldName );
                        if ( null == property ) {
                            property = new FieldAccessors( fieldName );
                            propertiesMap.put( fieldName, property );
                        }
                        property.addGetter( method );
                    }
                }
            }
        }

        for ( JClassType interf : type.getImplementedInterfaces() ) {
            parseMethods( interf, propertiesMap );
        }

        parseMethods( type.getSuperclass(), propertiesMap );
    }

    private String extractFieldNameFromGetterSetterMethodName( String methodName ) {
        if ( methodName.startsWith( "is" ) ) {
            return methodName.substring( 2, 3 ).toLowerCase() + methodName.substring( 3 );
        } else if ( methodName.startsWith( "get" ) || methodName.startsWith( "set" ) ) {
            return methodName.substring( 3, 4 ).toLowerCase() + methodName.substring( 4 );
        } else {
            return methodName;
        }
    }

    protected TypeParameters generateTypeParameterMapperFields( SourceWriter source, BeanInfo beanInfo, String mapperClass,
                                                                String mapperNameFormat ) throws UnableToCompleteException {
        if ( null == beanInfo.getParameterizedTypes() || beanInfo.getParameterizedTypes().length == 0 ) {
            return null;
        }

        List<String> typeParameterMapperNames = new ArrayList<String>();
        StringBuilder joinedTypeParameterMappersWithType = new StringBuilder();
        StringBuilder joinedTypeParameterMappersWithoutType = new StringBuilder();

        for ( int i = 0; i < beanInfo.getParameterizedTypes().length; i++ ) {
            if ( i > 0 ) {
                joinedTypeParameterMappersWithType.append( ", " );
                joinedTypeParameterMappersWithoutType.append( ", " );
            }

            JClassType argType = beanInfo.getParameterizedTypes()[i];
            String mapperType = String.format( "%s<%s>", mapperClass, argType.getName() );
            String mapperName = String.format( mapperNameFormat, i );

            source.println( "private final %s %s;", mapperType, mapperName );

            typeParameterMapperNames.add( mapperName );
            joinedTypeParameterMappersWithType.append( String.format( "%s %s%s", mapperType, TYPE_PARAMETER_PREFIX, mapperName ) );
            joinedTypeParameterMappersWithoutType.append( TYPE_PARAMETER_PREFIX ).append( mapperName );
        }

        return new TypeParameters( typeParameterMapperNames, joinedTypeParameterMappersWithType
            .toString(), joinedTypeParameterMappersWithoutType.toString() );
    }
}
