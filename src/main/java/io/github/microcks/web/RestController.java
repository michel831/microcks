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
package io.github.microcks.web;

import io.github.microcks.domain.Header;
import io.github.microcks.domain.Operation;
import io.github.microcks.domain.Response;
import io.github.microcks.domain.Service;
import io.github.microcks.event.MockInvocationEvent;
import io.github.microcks.repository.ResponseRepository;
import io.github.microcks.repository.ServiceRepository;
import io.github.microcks.util.DispatchCriteriaHelper;
import io.github.microcks.util.DispatchStyles;
import io.github.microcks.util.IdBuilder;
import io.github.microcks.util.dispatcher.JsonEvaluationSpecification;
import io.github.microcks.util.dispatcher.JsonExpressionEvaluator;
import io.github.microcks.util.dispatcher.JsonMappingException;
import io.github.microcks.util.soapui.SoapUIScriptEngineBinder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * A controller for mocking Rest responses.
 * @author laurent
 */
@org.springframework.web.bind.annotation.RestController
@RequestMapping("/rest")
public class RestController {

   /** A simple logger for diagnostic messages. */
   private static Logger log = LoggerFactory.getLogger(RestController.class);

   @Autowired
   private ServiceRepository serviceRepository;

   @Autowired
   private ResponseRepository responseRepository;

   @Autowired
   private ApplicationContext applicationContext;


   @RequestMapping(value = "/{service}/{version}/**")
   public ResponseEntity<?> execute(
         @PathVariable("service") String serviceName,
         @PathVariable("version") String version,
         @RequestParam(value="delay", required=false) Long delay,
         @RequestBody(required=false) String body,
         HttpServletRequest request
      ) {

      log.info("Servicing mock response for service [{}, {}] on uri {} with verb {}",
            serviceName, version, request.getRequestURI(), request.getMethod());
      log.debug("Request body: " + body);

      long startTime = System.currentTimeMillis();

      // Extract resourcePath for matching with correct operation.
      String requestURI = request.getRequestURI();
      String serviceAndVersion = null;
      String resourcePath = null;

      // Build the encoded URI fragment to retrieve simple resourcePath.
	 serviceAndVersion = "/" + UriUtils.encodeFragment(serviceName, "UTF-8") + "/" + version;
	 resourcePath = requestURI.substring(requestURI.indexOf(serviceAndVersion) + serviceAndVersion.length());
	 resourcePath = UriUtils.decode(resourcePath, "UTF-8");
      log.info("Found resourcePath: " + resourcePath);

      // If serviceName was encoded with '+' instead of '%20', replace them.
      if (serviceName.contains("+")) {
         serviceName = serviceName.replace('+', ' ');
      }
      // If resourcePath was encoded with '+' instead of '%20', replace them.
      if (resourcePath.contains("+")) {
         resourcePath = resourcePath.replace('+', ' ');
      }
      Service service = serviceRepository.findByNameAndVersion(serviceName, version);
      Operation rOperation = null;
      for (Operation operation : service.getOperations()){
         // Select operation based onto Http verb (GET, POST, PUT, etc ...)
         if (operation.getMethod().equals(request.getMethod().toUpperCase())){
            // ... then check is we have a matching resource path.
            if (operation.getResourcePaths() != null && operation.getResourcePaths().contains(resourcePath)){
               rOperation = operation;
               break;
            }
         }
      }

      if (rOperation != null){
         log.debug("Found a valid operation {} with rules: {}", rOperation.getName(), rOperation.getDispatcherRules());

         Response response = null;
         String uriPattern = getURIPattern(rOperation.getName());
         String dispatchCriteria = null;

         // Depending on dispatcher, evaluate request with rules.
         if (DispatchStyles.SEQUENCE.equals(rOperation.getDispatcher())){
            dispatchCriteria = DispatchCriteriaHelper.extractFromURIPattern(uriPattern, resourcePath);
         }
         else if (DispatchStyles.SCRIPT.equals(rOperation.getDispatcher())){
            ScriptEngineManager sem = new ScriptEngineManager();
            try{
               // Evaluating request with script coming from operation dispatcher rules.
               ScriptEngine se = sem.getEngineByExtension("groovy");
               SoapUIScriptEngineBinder.bindSoapUIEnvironment(se, body, request);
               dispatchCriteria = (String) se.eval(rOperation.getDispatcherRules());
            } catch (Exception e){
               log.error("Error during Script evaluation", e);
            }
         }
         // New cases related to services/operations/messages coming from a postman collection file.
         else if (DispatchStyles.URI_PARAMS.equals(rOperation.getDispatcher())){
            String fullURI = request.getRequestURL() + "?" + request.getQueryString();
            dispatchCriteria = DispatchCriteriaHelper.extractFromURIParams(rOperation.getDispatcherRules(), fullURI);
         }
         else if (DispatchStyles.URI_PARTS.equals(rOperation.getDispatcher())){
            dispatchCriteria = DispatchCriteriaHelper.extractFromURIPattern(uriPattern, resourcePath);
         }
         else if (DispatchStyles.URI_ELEMENTS.equals(rOperation.getDispatcher())){
            dispatchCriteria = DispatchCriteriaHelper.extractFromURIPattern(uriPattern, resourcePath);
            String fullURI = request.getRequestURL() + "?" + request.getQueryString();
            dispatchCriteria += DispatchCriteriaHelper.extractFromURIParams(rOperation.getDispatcherRules(), fullURI);
         }
         else if (DispatchStyles.JSON_BODY.equals(rOperation.getDispatcher())) {
            JsonEvaluationSpecification specification = null;
            try {
               specification = JsonEvaluationSpecification.buildFromJsonString(rOperation.getDispatcherRules());
               dispatchCriteria = JsonExpressionEvaluator.evaluate(body, specification);
            } catch (JsonMappingException jme) {
               log.error("Dispatching rules of request cannot be interpreted as valid JSON", jme);
            }
         }

         log.debug("Dispatch criteria for finding response is {}", dispatchCriteria);
         List<Response> responses = responseRepository.findByOperationIdAndDispatchCriteria(
               IdBuilder.buildOperationId(service, rOperation), dispatchCriteria);
         if (!responses.isEmpty()) {
            response = responses.get(0);
         } else {
            // When using the JSON_BODY dispatcher, return of evaluation may be the name of response.
            responses = responseRepository.findByOperationIdAndName(IdBuilder.buildOperationId(service, rOperation),
                  dispatchCriteria);
            if (!responses.isEmpty()) {
               response = responses.get(0);
            }
         }

         if (response != null) {
            // Setting delay to default one if not set.
            if (delay == null && rOperation.getDefaultDelay() != null) {
               delay = rOperation.getDefaultDelay();
            }

            if (delay != null && delay > -1) {
               log.debug("Mock delay is turned on, waiting if necessary...");
               long duration = System.currentTimeMillis() - startTime;
               if (duration < delay) {
                  Object semaphore = new Object();
                  synchronized (semaphore) {
                     try {
                        semaphore.wait(delay - duration);
                     } catch (Exception e) {
                        log.debug("Delay semaphore was interrupted");
                     }
                  }
                }
                log.debug("Delay now expired, releasing response !");
            }

            // Publish an invocation event before returning.
            MockInvocationEvent event = new MockInvocationEvent(this, service.getName(), version,
               response.getName(), new Date(startTime), startTime - System.currentTimeMillis());
            applicationContext.publishEvent(event);
            log.debug("Mock invocation event has been published");

            HttpStatus status = (response.getStatus() != null ?
                HttpStatus.valueOf(Integer.parseInt(response.getStatus())) : HttpStatus.OK);

            // Deal with specific headers (content-type and redirect directive).
            HttpHeaders responseHeaders = new HttpHeaders();
            if (response.getMediaType() != null) {
               responseHeaders.setContentType(MediaType.valueOf(response.getMediaType() + ";charset=UTF-8"));
            }

            // Adding other generic headers (caching directives and so on...)
            if (response.getHeaders() != null) {
               for (Header header : response.getHeaders()) {
                  if ("Location".equals(header.getName())) {
                     // We should process location in order to make relative URI specified an absolute one from
                     // the client perspective.
                     String location = "http://" + request.getServerName() + ":" + request.getServerPort()
                        + request.getContextPath() + "/rest"
                        + serviceAndVersion + header.getValues().iterator().next();
                     responseHeaders.add(header.getName(), location);
                  } else {
                     if (!HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(header.getName())) {
                        responseHeaders.put(header.getName(), new ArrayList<>(header.getValues()));
                     }
                  }
               }
            }
            return new ResponseEntity<Object>(response.getContent(), responseHeaders, status);
         }
         return new ResponseEntity<Object>(HttpStatus.BAD_REQUEST);
      }
      return new ResponseEntity<Object>(HttpStatus.NOT_FOUND);
   }


   private String getURIPattern(String operationName) {
      if (operationName.startsWith("GET ") || operationName.startsWith("POST ")
            || operationName.startsWith("PUT ") || operationName.startsWith("DELETE ")) {
         return operationName.substring(operationName.indexOf(' ') + 1);
      }
      return operationName;
   }
}
