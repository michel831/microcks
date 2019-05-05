/*
 * Licensed to Laurent Broudoux (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.github.microcks.util.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.microcks.domain.*;
import io.github.microcks.repository.ResourceRepository;
import io.github.microcks.repository.ResponseRepository;
import io.github.microcks.util.test.HttpTestRunner;
import io.github.microcks.util.test.TestReturn;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

/**
 * @author laurent
 */
public class OpenAPITestRunner extends HttpTestRunner {

   /** A simple logger for diagnostic messages. */
   private static Logger log = LoggerFactory.getLogger(OpenAPITestRunner.class);

   private ResourceRepository resourceRepository;
   private ResponseRepository responseRepository;
   private boolean validateResponseCode = false;

   private List<String> lastValidationErrors = null;

   /**
    *
    * @param validateResponseCode
    */
   public OpenAPITestRunner(ResourceRepository resourceRepository, ResponseRepository responseRepository, boolean validateResponseCode) {
      this.resourceRepository = resourceRepository;
      this.responseRepository = responseRepository;
      this.validateResponseCode = validateResponseCode;
   }

   /**
    * Build the HttpMethod corresponding to string.
    */
   @Override
   public HttpMethod buildMethod(String method){
      return HttpMethod.resolve(method.toUpperCase());
   }

   @Override
   protected int extractTestReturnCode(Service service, Operation operation, Request request,
                                       ClientHttpResponse httpResponse, String responseContent) {
      int code = TestReturn.SUCCESS_CODE;

      int responseCode = 0;
      try {
         responseCode = httpResponse.getRawStatusCode();
         log.debug("Response status code : " + responseCode);
      } catch (IOException ioe) {
         log.debug("IOException while getting raw status code in response", ioe);
         return TestReturn.FAILURE_CODE;
      }

      // If required, compare response code and content-type to expected ones.
      if (validateResponseCode) {
         Response expectedResponse = responseRepository.findById(request.getResponseId()).get();
         log.debug("Response expected status code : " + expectedResponse.getStatus());
         if (!String.valueOf(responseCode).equals(expectedResponse.getStatus())) {
            log.debug("Response HttpStatus does not match expected one, returning failure");
            return TestReturn.FAILURE_CODE;
         }

         log.debug("Response media-type is {}", httpResponse.getHeaders().getContentType().toString());
         if (!expectedResponse.getMediaType().equalsIgnoreCase(httpResponse.getHeaders().getContentType().toString())) {
            log.debug("Response Content-Type does not match expected one, returning failure");
            return TestReturn.FAILURE_CODE;
         }
      }

      // Retrieve the resource corresponding to OpenAPI specification if any.
      Resource openapiSpecResource = null;
      List<Resource> resources = resourceRepository.findByServiceId(service.getId());
      for (Resource resource : resources) {
         if (ResourceType.OPEN_API_SPEC.equals(resource.getType())) {
            openapiSpecResource = resource;
            break;
         }
      }
      if (openapiSpecResource == null) {
         log.debug("Do not found any OpenAPI specification resource for service {0}, so failing validating", service.getId());
         return TestReturn.FAILURE_CODE;
      }

      JsonNode openapiSpec = null;
      try {
         openapiSpec = OpenAPISchemaValidator.getJsonNodeForSchema(openapiSpecResource.getContent());
      } catch (IOException ioe) {
         log.debug("OpenAPI specification cannot be transformed into valid JsonNode schema, so failing");
         return TestReturn.FAILURE_CODE;
      }

      // Extract JsonNode corresponding to response.
      String verb = operation.getName().split(" ")[0].toLowerCase();
      String path = operation.getName().split(" ")[1].trim();
      MediaType mediaType = httpResponse.getHeaders().getContentType();
      log.debug("Response media-type is {}", mediaType.toString());

      String pointer = "/paths/" + path.replace("/", "~1") + "/" + verb
            + "/responses/" + responseCode + "/content/" + mediaType.toString().replace("/", "~1");
      JsonNode responseNode = openapiSpec.at(pointer);
      log.debug("responseNode: " + responseNode);

      // Is there a specified responseNode for this type ??
      if (responseNode != null && !responseNode.isMissingNode()) {
         // Get body content as a string.
         JsonNode contentNode = null;
         try {
            contentNode = OpenAPISchemaValidator.getJsonNode(responseContent);
         } catch (IOException ioe) {
            log.debug("Response body cannot be accessed or transformed as Json, returning failure");
            return TestReturn.FAILURE_CODE;
         }

         // Build a schema object with responseNode schema as root and by importing
         // all the common parts that may be referenced by references.
         JsonNode schemaNode = responseNode.path("schema").deepCopy();
         ((ObjectNode) schemaNode).set("components", openapiSpec.path("components").deepCopy());

         lastValidationErrors = OpenAPISchemaValidator.validateJson(schemaNode, contentNode);
         if (!lastValidationErrors.isEmpty()) {
            log.debug("OpenAPI schema validation errors found " + lastValidationErrors.size() + ", marking test as failed.");
            return TestReturn.FAILURE_CODE;
         }
         log.debug("OpenAPI schema validation of response is successful !");
      } else {
         // Do we still have a response body ??
         if (httpResponse.getHeaders().getContentLength() > 0) {
            log.debug("No response expected or defined but response has content, failing");
            code = TestReturn.FAILURE_CODE;
         }
      }
      return code;
   }

   @Override
   protected String extractTestReturnMessage(Service service, Operation operation, Request request, ClientHttpResponse httpResponse) {
      StringBuilder builder = new StringBuilder();
      if (lastValidationErrors != null && !lastValidationErrors.isEmpty()) {
         for (String error : lastValidationErrors) {
            builder.append(error).append("/n");
         }
      }
      // Reset just after consumption so avoid side-effects.
      lastValidationErrors = null;
      return builder.toString();
   }
}
