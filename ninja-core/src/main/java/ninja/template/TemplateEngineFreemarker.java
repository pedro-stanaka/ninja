/**
 * Copyright (C) 2012-2014 the original author or authors.
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

package ninja.template;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import ninja.Context;
import ninja.Result;
import ninja.i18n.Lang;
import ninja.i18n.Messages;
import ninja.utils.NinjaProperties;
import ninja.utils.ResponseStreams;

import org.slf4j.Logger;

import com.google.common.base.CaseFormat;
import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.google.inject.Inject;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.ext.beans.BeansWrapper;
import freemarker.ext.beans.BeansWrapperBuilder;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapper;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.Template;
import freemarker.template.Version;
import java.io.Writer;
import javax.inject.Singleton;
import ninja.utils.NinjaConstant;

@Singleton
public class TemplateEngineFreemarker implements TemplateEngine {
    
    // Selection of logging library has to be done manually until Freemarker 2.4
    // more: http://freemarker.org/docs/api/freemarker/log/Logger.html
    static {
        try {
            freemarker.log.Logger.selectLoggerLibrary(freemarker.log.Logger.LIBRARY_SLF4J);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    // end
    
    private final Version INCOMPATIBLE_IMPROVEMENTS_VERSION = new Version(2, 3, 21);

    private final String FILE_SUFFIX = ".ftl.html";

    private final Configuration cfg;

    private final Messages messages;
    
    private final Lang lang;

    private final TemplateEngineHelper templateEngineHelper;

    private final Logger logger;

    private final TemplateEngineFreemarkerExceptionHandler templateEngineFreemarkerExceptionHandler;

    private final TemplateEngineFreemarkerReverseRouteMethod templateEngineFreemarkerReverseRouteMethod;
            
    private final TemplateEngineFreemarkerAssetsAtMethod templateEngineFreemarkerAssetsAtMethod;
    
    private final TemplateEngineFreemarkerWebJarsAtMethod templateEngineFreemarkerWebJarsAtMethod;
    
    @Inject
    public TemplateEngineFreemarker(Messages messages,
                                    Lang lang,
                                    Logger logger,
                                    TemplateEngineFreemarkerExceptionHandler templateEngineFreemarkerExceptionHandler,
                                    TemplateEngineHelper templateEngineHelper,
                                    TemplateEngineManager templateEngineManager,
                                    TemplateEngineFreemarkerReverseRouteMethod templateEngineFreemarkerReverseRouteMethod,
                                    TemplateEngineFreemarkerAssetsAtMethod templateEngineFreemarkerAssetsAtMethod,
                                    TemplateEngineFreemarkerWebJarsAtMethod templateEngineFreemarkerWebJarsAtMethod,
                                    NinjaProperties ninjaProperties) throws Exception {
        this.messages = messages;
        this.lang = lang;
        this.logger = logger;
        this.templateEngineFreemarkerExceptionHandler = templateEngineFreemarkerExceptionHandler;
        this.templateEngineHelper = templateEngineHelper;
        this.templateEngineFreemarkerReverseRouteMethod = templateEngineFreemarkerReverseRouteMethod;
        this.templateEngineFreemarkerAssetsAtMethod = templateEngineFreemarkerAssetsAtMethod;
        this.templateEngineFreemarkerWebJarsAtMethod = templateEngineFreemarkerWebJarsAtMethod;
        
        cfg = new Configuration(INCOMPATIBLE_IMPROVEMENTS_VERSION);
        
        // Set your preferred charset template files are stored in. UTF-8 is
        // a good choice in most applications:
        cfg.setDefaultEncoding(NinjaConstant.UTF_8);
        
        // Set the charset of the output. This is actually just a hint, that
        // templates may require for URL encoding and for generating META element
        // that uses http-equiv="Content-type".
        cfg.setOutputEncoding(NinjaConstant.UTF_8);
        
        // Ninja does the localization itself - lookup is not needed.
        cfg.setLocalizedLookup(false);

        cfg.setTemplateExceptionHandler(templateEngineFreemarkerExceptionHandler);

        ///////////////////////////////////////////////////////////////////////
        // 1) In dev we load templates from src/java/main first, then from the
        //    classpath.
        //    Therefore Freemarker can handle reloading of changed templates without
        //    the need to restart the server (e.g automatic reload of jetty:run) 
        // 2) In test and prod we never refresh templates and load them
        //    from the classpath
        ///////////////////////////////////////////////////////////////////////      
        String srcDir 
        = System.getProperty("user.dir")
            + File.separator 
            + "src" 
            + File.separator 
            + "main" 
            + File.separator
            + "java";        
                
        if (ninjaProperties.isDev()
                && new File(srcDir).exists()) {
            
            try {
                // the src dir of user's project.
                FileTemplateLoader fileTemplateLoader = new FileTemplateLoader(new File(srcDir));
                // then ftl.html files from the classpath (eg. from inherited modules
                // or the ninja core module)
                ClassTemplateLoader classTemplateLoader = new ClassTemplateLoader(this.getClass(), "/");
                
                TemplateLoader [] templateLoader = new TemplateLoader[] { 
                        fileTemplateLoader, 
                        classTemplateLoader };
                
                MultiTemplateLoader multiTemplateLoader = new MultiTemplateLoader(templateLoader);
                
                cfg.setTemplateLoader(multiTemplateLoader);  
                
            } catch (IOException e) {
                logger.error("Error Loading Freemarker Template " +srcDir , e);
            }
            
            // check for updates each second
            cfg.setTemplateUpdateDelay(1);


        } else {
            // load templates from classpath
            cfg.setClassForTemplateLoading(this.getClass(), "/");
            
            // never update the templates in production or while testing...
            cfg.setTemplateUpdateDelay(Integer.MAX_VALUE);
            
            // Hold 20 templates as strong references as recommended by:
            // http://freemarker.sourceforge.net/docs/pgui_config_templateloading.html
            cfg.setCacheStorage(new freemarker.cache.MruCacheStorage(20, Integer.MAX_VALUE));

        }
        
        // we are going to enable html escaping by default using this template
        // loader:
        cfg.setTemplateLoader(new TemplateEngineFreemarkerEscapedLoader(cfg
                .getTemplateLoader()));
        
        
        // We also do not want Freemarker to chose a platform dependent
        // number formatting. Eg "1000" could be printed out by FTL as "1,000"
        // on some platforms. This is not "least astonishemnt". It will also
        // break stuff really badly sometimes.
        // See also: http://freemarker.sourceforge.net/docs/app_faq.html#faq_number_grouping
        cfg.setNumberFormat("0.######");  // now it will print 1000000
        

        cfg.setObjectWrapper(createBeansWrapperWithExposedFields());
        
    }

    @Override
    public void invoke(Context context, Result result) {

        Object object = result.getRenderable();

        Map map;
        // if the object is null we simply render an empty map...
        if (object == null) {            
            map = Maps.newHashMap();
            
        } else if (object instanceof Map) {            
            map = (Map) object;
            
        } else {
            // We are getting an arbitrary Object and put that into
            // the root of freemarker
            
            // If you are rendering something like Results.ok().render(new MyObject())
            // Assume MyObject has a public String name field.            
            // You can then access the fields in the template like that:
            // ${myObject.publicField}            
            
            String realClassNameLowerCamelCase = CaseFormat.UPPER_CAMEL.to(
                    CaseFormat.LOWER_CAMEL, object.getClass().getSimpleName());
            
            map = Maps.newHashMap();
            map.put(realClassNameLowerCamelCase, object);
            
        }
        
        // set language from framework. You can access
        // it in the templates as ${lang}
        Optional<String> language = lang.getLanguage(context, Optional.of(result));
        if (language.isPresent()) {
            map.put("lang", language.get());
        }

        // put all entries of the session cookie to the map.
        // You can access the values by their key in the cookie
        if (!context.getSession().isEmpty()) {
            map.put("session", context.getSession().getData());
        }
        
        map.put("contextPath", context.getContextPath());
        
        
        //////////////////////////////////////////////////////////////////////
        // A method that renders i18n messages and can also render messages with 
        // placeholders directly in your template:
        // E.g.: ${i18n("mykey", myPlaceholderVariable)}
        //////////////////////////////////////////////////////////////////////
        map.put("i18n", new TemplateEngineFreemarkerI18nMethod(messages, context, result));

        Optional<String> requestLang = lang.getLanguage(context, Optional.of(result));
        Locale locale = lang.getLocaleFromStringOrDefault(requestLang);
        map.put("prettyTime", new TemplateEngineFreemarkerPrettyTimeMethod(locale));

        map.put("reverseRoute", templateEngineFreemarkerReverseRouteMethod);
        map.put("assetsAt", templateEngineFreemarkerAssetsAtMethod);
        map.put("webJarsAt", templateEngineFreemarkerWebJarsAtMethod);

        ///////////////////////////////////////////////////////////////////////
        // Convenience method to translate possible flash scope keys.
        // !!! If you want to set messages with placeholders please do that
        // !!! in your controller. We only can set simple messages.
        // Eg. A message like "errorMessage=my name is: {0}" => translate in controller and pass directly.
        //     A message like " errorMessage=An error occurred" => use that as errorMessage.  
        //
        // get keys via ${flash.KEYNAME}
        //////////////////////////////////////////////////////////////////////
        Map<String, String> translatedFlashCookieMap = Maps.newHashMap();
        for (Entry<String, String> entry : context.getFlashScope().getCurrentFlashCookieData().entrySet()) {
            
            String messageValue = null;

                
            Optional<String> messageValueOptional = messages.get(entry.getValue(), context, Optional.of(result));
                
            if (!messageValueOptional.isPresent()) {
                messageValue = entry.getValue();
            } else {
                messageValue = messageValueOptional.get();
            }
            // new way
            translatedFlashCookieMap.put(entry.getKey(), messageValue);
        }
        
        // now we can retrieve flash cookie messages via ${flash.MESSAGE_KEY}
        map.put("flash", translatedFlashCookieMap);        

        // Specify the data source where the template files come from.
        // Here I set a file directory for it:
        String templateName = templateEngineHelper.getTemplateForResult(
                context.getRoute(), result, FILE_SUFFIX);

        
        Template freemarkerTemplate = null;
        
        try {
            
            freemarkerTemplate = cfg.getTemplate(templateName);
            
        } catch (IOException iOException) {
            
            logger.error(
                    "Error reading Freemarker Template {} ", templateName, iOException);
            
            throw new RuntimeException(iOException);
        }
        
        
        ResponseStreams responseStreams = context.finalizeHeaders(result);

        try (Writer writer = responseStreams.getWriter()) {
            
            freemarkerTemplate.process(map, writer);

        } catch (Exception e) {
            
            logger.error(
                    "Error processing Freemarker Template {} ", templateName, e);
            
            throw new RuntimeException(e);
            
        }

    }

    @Override
    public String getContentType() {
        return "text/html";
    }

    @Override
    public String getSuffixOfTemplatingEngine() {
        return FILE_SUFFIX;
    }
    
    private BeansWrapper createBeansWrapperWithExposedFields() {
        DefaultObjectWrapperBuilder defaultObjectWrapperBuilder 
            = new DefaultObjectWrapperBuilder(INCOMPATIBLE_IMPROVEMENTS_VERSION);
        defaultObjectWrapperBuilder.setExposeFields(true);
        DefaultObjectWrapper defaultObjectWrapper = defaultObjectWrapperBuilder.build();
        return defaultObjectWrapper;
    }
    
}
