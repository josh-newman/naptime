/*
 * Copyright 2016 Coursera Inc.
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

package org.coursera.naptime.ari.graphql

import com.linkedin.data.DataMap
import com.linkedin.data.schema.RecordDataSchema
import com.typesafe.scalalogging.StrictLogging
import org.coursera.naptime.PaginationConfiguration
import org.coursera.naptime.ResourceName
import org.coursera.naptime.ari.graphql.schema.NaptimePaginatedResourceField
import org.coursera.naptime.ari.graphql.schema.NaptimePaginationField
import org.coursera.naptime.ari.graphql.schema.NaptimeResourceField
import org.coursera.naptime.ari.graphql.schema.SchemaMetadata
import org.coursera.naptime.schema.Handler
import org.coursera.naptime.schema.HandlerKind
import org.coursera.naptime.schema.Resource
import sangria.marshalling.FromInput
import sangria.schema.Argument
import sangria.schema.BigDecimalType
import sangria.schema.BooleanType
import sangria.schema.Context
import sangria.schema.FloatType
import sangria.schema.InputType
import sangria.schema.IntType
import sangria.schema.ListInputType
import sangria.schema.LongType
import sangria.schema.Schema
import sangria.schema.StringType
import sangria.schema.Value
import sangria.marshalling.FromInput._
import sangria.schema.Field
import sangria.schema.ObjectType
import sangria.schema.OptionInputType

class SangriaGraphQlSchemaBuilder(
    resources: Set[Resource],
    schemas: Map[String, RecordDataSchema])
  extends StrictLogging {

  val schemaMetadata = SchemaMetadata(resources, schemas)

  /**
    * Generates a GraphQL schema for the provided set of resources to this class
    * Returns a "root" object that has one field available for each Naptime Resource provided.*
    *
    * @return a Sangria GraphQL Schema with all resources defined
    */
  def generateSchema(): Schema[SangriaGraphQlContext, DataMap] = {
    val topLevelResourceObjects = for {
      resource <- resources
      resourceObject <- (try {
        val resourceName = ResourceName(
          resource.name, resource.version.getOrElse(0L).toInt).identifier
        generateLookupTypeForResource(resourceName)
      } catch {
        case e: Throwable => None
      }).toList if resourceObject.fields.nonEmpty
    } yield {
      Field.apply[SangriaGraphQlContext, DataMap, DataMap, Any](
        formatResourceTopLevelName(resource),
        resourceObject,
        resolve = (context: Context[SangriaGraphQlContext, DataMap]) => {
          Value(null)
        })
    }

    val dedupedResources = topLevelResourceObjects.groupBy(_.name).map(_._2.head).toList
    val rootObject = ObjectType[SangriaGraphQlContext, DataMap](
      name = "root",
      description = "Top-level accessor for Naptime resources",
      fields = dedupedResources)
    Schema(rootObject)
  }

  def scalaTypeToSangria(typeName: String): InputType[Any] = {
    import sangria.marshalling.FromInput.seqInput
    import sangria.marshalling.FromInput.coercedScalaInput

    val listPattern = "(Set|List|Seq|immutable.Seq)\\[(.*)\\]".r
    val optionPattern = "(Option)\\[(.*)\\]".r
    // TODO(bryan): Fill in the missing types here
    typeName match {
      case listPattern(outerType, innerType) => ListInputType(scalaTypeToSangria(innerType))
      case optionPattern(outerType, innerType) => OptionInputType(scalaTypeToSangria(innerType))
      case "string" | "String" => StringType
      case "int" | "Int" => IntType
      case "long" | "Long" => LongType
      case "float" | "Float" => FloatType
      case "decimal" | "Decimal" => BigDecimalType
      case "boolean" | "Boolean" => BooleanType
      case _ => {
        logger.warn(s"could not parse type from $typeName")
        StringType
      }
    }
  }

  def scalaTypeToFromInput(typeName: String): FromInput[Any] = {
    import sangria.marshalling.FromInput.seqInput
    import sangria.marshalling.FromInput.coercedScalaInput

    val listPattern = "(set|list|seq|immutable.Seq)\\[(.*)\\]".r
    val optionPattern = "(Option)\\[(.*)\\]".r

    // TODO(bryan): Fix all of this :)
    typeName.toLowerCase match {
      case listPattern(outerType, innerType) =>
        sangria.marshalling.FromInput.seqInput.asInstanceOf[FromInput[Any]]
      case "string" | "int" | "long" | "float" | "decimal" | "boolean" =>
        sangria.marshalling.FromInput.coercedScalaInput.asInstanceOf[FromInput[Any]]
      case _ =>
        sangria.marshalling.FromInput.coercedScalaInput.asInstanceOf[FromInput[Any]]
    }
  }

  /**
    * Generates an object-type for a given resource name, with each field on the merged output
    * schema available on this object-type.
    *
    * @param resourceName String name of the resource (i.e. 'courses.v1')
    * @return ObjectType for the resource
    */
  def generateLookupTypeForResource(resourceName: String): Option[ObjectType[SangriaGraphQlContext, DataMap]] = {

    try {
      val resource = schemaMetadata.getResource(resourceName)
      val fields = resource.handlers.flatMap { handler =>
        handler.kind match {
          case HandlerKind.GET =>
            Some(generateGetHandler(resource, handler))
          case HandlerKind.GET_ALL | HandlerKind.MULTI_GET | HandlerKind.FINDER =>
            Some(generateListHandler(resource, handler))
          case _ => None
        }
      }.toList
      if (fields.nonEmpty) {
        val resourceObjectType = ObjectType[SangriaGraphQlContext, DataMap](
          name = formatResourceTopLevelName(resource),
          fieldsFn = () => fields)
        Some(resourceObjectType)
      } else {
        logger.warn(s"No handlers available for resource $resourceName")
        None
      }
    } catch {
      case e: Throwable =>
        logger.error(s"Unknown error when generating resource: ${e.getMessage}")
        None
    }
  }

  def generateGetHandler(
      resource: Resource,
      handler: Handler): Field[SangriaGraphQlContext, DataMap] = {
    val arguments = generateHandlerArguments(handler)
    val resourceName = ResourceName(resource.name, resource.version.getOrElse(0L).toInt)

    val idExtractor = (context: Context[SangriaGraphQlContext, DataMap]) => context.arg[AnyRef]("id")

    val field = NaptimeResourceField.build(
      schemaMetadata = schemaMetadata,
      resourceName = resourceName.identifier,
      fieldName = "get",
      idExtractor = Some(idExtractor))

    field.copy(arguments = arguments ++ field.arguments)
  }

  def generateListHandler(
      resource: Resource,
      handler: Handler): Field[SangriaGraphQlContext, DataMap] = {
    val arguments = generateHandlerArguments(handler)
    val resourceName = ResourceName(resource.name, resource.version.getOrElse(0L).toInt)

    val fieldName = handler.kind match {
      case HandlerKind.FINDER => handler.name
      case HandlerKind.GET_ALL => "getAll"
      case HandlerKind.MULTI_GET => "multiGet"
      case _ => "error"
    }

    val field = NaptimePaginatedResourceField.build(
      schemaMetadata = schemaMetadata,
      resourceName = resourceName.identifier,
      fieldName = fieldName)

    field.copy(arguments = arguments ++ field.arguments)
  }

  val PAGINATION_ARGUMENT_NAMES = NaptimePaginationField.paginationArguments.map(_.name)

  def generateHandlerArguments(handler: Handler): List[Argument[Any]] = {
    handler.parameters
      .filterNot(parameter => PAGINATION_ARGUMENT_NAMES.contains(parameter.name))
      .map { parameter =>
        val tpe = parameter.`type`
        // TODO(bryan): Use argument defaults here
        Argument(
          name = parameter.name,
          argumentType = scalaTypeToSangria(tpe))(scalaTypeToFromInput(tpe), implicitly).asInstanceOf[Argument[Any]]
      }.toList
  }


  /**
    * Converts a resource name to a GraphQL compatible name. (i.e. 'courses.v1' to 'CoursesV1')
    *
    * @param resource Naptime resource
    * @return GraphQL-safe resource name
    */
  def formatResourceName(resource: Resource): String = {
    s"${resource.name.capitalize}V${resource.version.getOrElse(0)}"
  }

  /**
    * Converts a resource to a GraphQL top-level name. (i.e. 'courses.v1' to 'CoursesV1Resource')
    *
    * @param resource Naptime resource
    * @return GraphQL-safe top-level resource name
    */
  def formatResourceTopLevelName(resource: Resource): String = {
    s"${formatResourceName(resource)}Resource"
  }
}
