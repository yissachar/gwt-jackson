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

import javax.lang.model.element.Modifier;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.github.nmorel.gwtjackson.client.JsonSerializationContext;
import com.github.nmorel.gwtjackson.client.JsonSerializer;
import com.github.nmorel.gwtjackson.client.ser.RawValueJsonSerializer;
import com.github.nmorel.gwtjackson.client.ser.bean.AbstractBeanJsonSerializer;
import com.github.nmorel.gwtjackson.client.ser.bean.AnyGetterPropertySerializer;
import com.github.nmorel.gwtjackson.client.ser.bean.BeanPropertySerializer;
import com.github.nmorel.gwtjackson.client.ser.bean.IdentitySerializationInfo;
import com.github.nmorel.gwtjackson.client.ser.bean.SubtypeSerializer;
import com.github.nmorel.gwtjackson.client.ser.bean.SubtypeSerializer.BeanSubtypeSerializer;
import com.github.nmorel.gwtjackson.client.ser.bean.SubtypeSerializer.DefaultSubtypeSerializer;
import com.github.nmorel.gwtjackson.client.ser.bean.TypeSerializationInfo;
import com.github.nmorel.gwtjackson.client.stream.JsonWriter;
import com.github.nmorel.gwtjackson.rebind.exception.UnsupportedTypeException;
import com.github.nmorel.gwtjackson.rebind.property.PropertyInfo;
import com.github.nmorel.gwtjackson.rebind.type.JSerializerType;
import com.github.nmorel.gwtjackson.rebind.writer.JClassName;
import com.google.gwt.core.ext.GeneratorContext;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.TreeLogger.Type;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.thirdparty.guava.common.base.Optional;
import com.google.gwt.thirdparty.guava.common.collect.ImmutableList;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;

import static com.github.nmorel.gwtjackson.rebind.CreatorUtils.escapeString;

/**
 * @author Nicolas Morel
 */
public class BeanJsonSerializerCreator extends AbstractBeanJsonCreator {

    public BeanJsonSerializerCreator( TreeLogger logger, GeneratorContext context, RebindConfiguration configuration, JacksonTypeOracle
            typeOracle, JClassType beanType ) throws UnableToCompleteException {
        super( logger, context, configuration, typeOracle, beanType );
    }

    @Override
    protected boolean isSerializer() {
        return true;
    }

    @Override
    protected void buildSpecific() throws UnableToCompleteException {

        if ( !properties.isEmpty() ) {
            if ( beanInfo.getValuePropertyInfo().isPresent() ) {
                typeBuilder.addMethod( buildInitValueSerializerMethod( beanInfo.getValuePropertyInfo().get() ) );
            } else {
                Map<PropertyInfo, JSerializerType> propertiesMap = new LinkedHashMap<PropertyInfo, JSerializerType>();
                for ( PropertyInfo propertyInfo : properties.values() ) {
                    JSerializerType serializerType = getJsonSerializerFromProperty( propertyInfo );
                    if ( null != serializerType ) {
                        propertiesMap.put( propertyInfo, serializerType );
                    }
                }
                if ( !propertiesMap.isEmpty() ) {
                    typeBuilder.addMethod( buildInitSerializersMethod( propertiesMap ) );
                }
            }
        }

        if ( beanInfo.getAnyGetterPropertyInfo().isPresent() ) {
            typeBuilder.addMethod( buildInitAnyGetterPropertySerializerMethod( beanInfo.getAnyGetterPropertyInfo().get() ) );
        }

        if ( beanInfo.getIdentityInfo().isPresent() ) {
            try {
                Optional<JSerializerType> serializerType = getIdentitySerializerType( beanInfo.getIdentityInfo().get() );
                typeBuilder.addMethod( buildInitIdentityInfoMethod( serializerType ) );
            } catch ( UnsupportedTypeException e ) {
                logger.log( Type.WARN, "Identity type is not supported. We ignore it." );
            }
        }

        if ( beanInfo.getTypeInfo().isPresent() ) {
            typeBuilder.addMethod( buildInitTypeInfoMethod() );
        }

        ImmutableList<JClassType> subtypes = filterSubtypes( beanInfo );
        if ( !subtypes.isEmpty() ) {
            typeBuilder.addMethod( buildInitMapSubtypeClassToSerializerMethod( subtypes ) );
        }
    }

    private JSerializerType getJsonSerializerFromProperty( PropertyInfo propertyInfo ) throws UnableToCompleteException {
        if ( null != propertyInfo && propertyInfo.getGetterAccessor().isPresent() && !propertyInfo.isIgnored() ) {
            if ( propertyInfo.isRawValue() ) {
                return new JSerializerType.Builder().type( propertyInfo.getType() ).instance( CodeBlock.builder()
                        .add( "$T.<$T>getInstance()", RawValueJsonSerializer.class, JClassName.get( propertyInfo.getType() ) ).build() )
                        .build();
            } else {
                try {
                    return getJsonSerializerFromType( propertyInfo.getType() );
                } catch ( UnsupportedTypeException e ) {
                    logger.log( Type.WARN, "Property '" + propertyInfo.getPropertyName() + "' is ignored." );
                }
            }
        }
        return null;
    }

    private MethodSpec buildInitValueSerializerMethod( PropertyInfo propertyInfo ) throws UnableToCompleteException {
        return MethodSpec.methodBuilder( "initValueSerializer" )
                .addModifiers( Modifier.PROTECTED )
                .addAnnotation( Override.class )
                .returns( ParameterizedTypeName.get( ClassName.get( BeanPropertySerializer.class ), JClassName.get( beanInfo
                        .getType() ), WildcardTypeName.subtypeOf( Object.class ) ) )
                .addStatement( "return $L", buildSerializer( propertyInfo, getJsonSerializerFromProperty( propertyInfo ) ) )
                .build();
    }

    private MethodSpec buildInitSerializersMethod( Map<PropertyInfo, JSerializerType> properties )
            throws UnableToCompleteException {
        MethodSpec.Builder builder = MethodSpec.methodBuilder( "initSerializers" )
                .addModifiers( Modifier.PROTECTED )
                .addAnnotation( Override.class )
                .returns( ArrayTypeName.of( BeanPropertySerializer.class ) )
                .addStatement( "$T result = new $T[$L]",
                        ArrayTypeName.of( BeanPropertySerializer.class ), BeanPropertySerializer.class, properties.size() );

        int i = 0;
        for ( Entry<PropertyInfo, JSerializerType> entry : properties.entrySet() ) {
            builder.addStatement( "result[$L] = $L", i++, buildSerializer( entry.getKey(), entry.getValue() ) );
        }

        builder.addStatement( "return result" );
        return builder.build();
    }

    private MethodSpec buildInitAnyGetterPropertySerializerMethod( PropertyInfo anyGetterPropertyInfo ) throws
            UnableToCompleteException {

        return MethodSpec.methodBuilder( "initAnyGetterPropertySerializer" )
                .addModifiers( Modifier.PROTECTED )
                .addAnnotation( Override.class )
                .returns( JClassName.get( AnyGetterPropertySerializer.class, beanInfo.getType() ) )
                .addStatement( "return $L",
                        buildSerializer( anyGetterPropertyInfo, getJsonSerializerFromProperty( anyGetterPropertyInfo ) ) )
                .build();

    }

    private TypeSpec buildSerializer( PropertyInfo property, JSerializerType serializerType ) throws UnableToCompleteException {

        TypeSpec.Builder builder;

        String escapedPropertyName = escapeString( property.getPropertyName() );

        if ( property.isAnyGetter() ) {
            builder = TypeSpec.anonymousClassBuilder( "" )
                    .superclass( JClassName.get( AnyGetterPropertySerializer.class, beanInfo.getType() ) );
        } else {
            builder = TypeSpec.anonymousClassBuilder( "\"$L\"", escapedPropertyName )
                    .superclass( JClassName.get( BeanPropertySerializer.class, beanInfo.getType(), property.getType() ) );
        }

        buildBeanPropertySerializerBody( builder, beanInfo.getType(), property, serializerType );

        boolean requireEscaping = !property.getPropertyName().equals( escapedPropertyName );
        if ( property.isUnwrapped() || requireEscaping ) {
            MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder( "serializePropertyName" )
                    .addModifiers( Modifier.PUBLIC )
                    .addAnnotation( Override.class )
                    .addParameter( JsonWriter.class, "writer" )
                    .addParameter( JClassName.get( beanInfo.getType() ), "bean" )
                    .addParameter( JsonSerializationContext.class, "ctx" );
            if ( !property.isUnwrapped() ) {
                methodBuilder.addStatement( "writer.name( propertyName )" );
            }
            builder.addMethod( methodBuilder.build() );
        }

        return builder.build();
    }

    private MethodSpec buildInitIdentityInfoMethod( Optional<JSerializerType> serializerType )
            throws UnableToCompleteException, UnsupportedTypeException {
        return MethodSpec.methodBuilder( "initIdentityInfo" )
                .addModifiers( Modifier.PROTECTED )
                .addAnnotation( Override.class )
                .returns( JClassName.get( IdentitySerializationInfo.class, beanInfo.getType() ) )
                .addStatement( "return $L",
                        generateIdentifierSerializationInfo( beanInfo.getType(), beanInfo.getIdentityInfo().get(), serializerType ) )
                .build();
    }

    private MethodSpec buildInitTypeInfoMethod() {
        return MethodSpec.methodBuilder( "initTypeInfo" )
                .addModifiers( Modifier.PROTECTED )
                .addAnnotation( Override.class )
                .returns( JClassName.get( TypeSerializationInfo.class, beanInfo.getType() ) )
                .addStatement( "return $L", generateTypeInfo( beanInfo.getTypeInfo().get(), true ) )
                .build();
    }

    private MethodSpec buildInitMapSubtypeClassToSerializerMethod( ImmutableList<JClassType> subtypes ) throws
            UnableToCompleteException {

        Class[] mapTypes = new Class[]{Class.class, SubtypeSerializer.class};
        TypeName resultType = ParameterizedTypeName.get( Map.class, mapTypes );

        MethodSpec.Builder builder = MethodSpec.methodBuilder( "initMapSubtypeClassToSerializer" )
                .addModifiers( Modifier.PROTECTED )
                .addAnnotation( Override.class )
                .returns( resultType )
                .addStatement( "$T map = new $T($L)",
                        resultType, ParameterizedTypeName.get( IdentityHashMap.class, mapTypes ), subtypes.size() );

        for ( JClassType subtype : subtypes ) {

            JSerializerType serializerType;
            try {
                serializerType = getJsonSerializerFromType( subtype, true );
            } catch ( UnsupportedTypeException e ) {
                logger.log( Type.WARN, "Subtype '" + subtype.getQualifiedSourceName() + "' is not supported. We ignore it." );
                continue;
            }

            Class subtypeClass;
            TypeName serializerClass;
            if ( configuration.getSerializer( subtype ).isPresent()
                    || null != subtype.isEnum()
                    || Enum.class.getName().equals( subtype.getQualifiedSourceName() ) ) {
                subtypeClass = DefaultSubtypeSerializer.class;
                serializerClass = ParameterizedTypeName.get(
                        ClassName.get( JsonSerializer.class ), WildcardTypeName.subtypeOf( Object.class ) );
            } else {
                subtypeClass = BeanSubtypeSerializer.class;
                serializerClass = ParameterizedTypeName.get(
                        ClassName.get( AbstractBeanJsonSerializer.class ), WildcardTypeName.subtypeOf( Object.class ) );
            }

            TypeSpec subtypeType = TypeSpec.anonymousClassBuilder( "" )
                    .superclass( subtypeClass )
                    .addMethod( MethodSpec.methodBuilder( "newSerializer" )
                                    .addModifiers( Modifier.PROTECTED )
                                    .addAnnotation( Override.class )
                                    .returns( serializerClass )
                                    .addStatement( "return $L", serializerType.getInstance() )
                                    .build()
                    ).build();

            builder.addStatement( "map.put($T.class, $L)", JClassName.getRaw( subtype ), subtypeType );
        }

        builder.addStatement( "return map" );
        return builder.build();
    }
}
