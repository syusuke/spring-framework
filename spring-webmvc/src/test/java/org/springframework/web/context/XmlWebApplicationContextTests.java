/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.context;

import java.util.Locale;

import javax.servlet.ServletException;

import org.junit.jupiter.api.Test;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.AbstractApplicationContextTests;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.TestListener;
import org.springframework.mock.web.test.MockServletContext;
import org.springframework.tests.sample.beans.TestBean;
import org.springframework.util.Assert;
import org.springframework.web.context.support.XmlWebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author Rod Johnson
 * @author Juergen Hoeller
 */
public class XmlWebApplicationContextTests extends AbstractApplicationContextTests {

    private ConfigurableWebApplicationContext root;

    @Override
    protected ConfigurableApplicationContext createContext() throws Exception {
        InitAndIB.constructed = false;
        // root IoC Context 为 Web
        root = new XmlWebApplicationContext();
        root.getEnvironment().addActiveProfile("rootProfile1");
        // Mock 一个 ServletContext
        MockServletContext sc = new MockServletContext("");
        // root set ServletContext
        root.setServletContext(sc);
        // set 配置文件
        root.setConfigLocations(new String[]{"/org/springframework/web/context/WEB-INF/applicationContext.xml"});
        // 添加Bean后置处理器
        root.addBeanFactoryPostProcessor(new BeanFactoryPostProcessor() {
            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
                beanFactory.addBeanPostProcessor(new BeanPostProcessor() {
                    @Override
                    public Object postProcessBeforeInitialization(Object bean, String name) throws BeansException {
                        if (bean instanceof TestBean) {
                            ((TestBean) bean).getFriends().add("myFriend");
                        }
                        return bean;
                    }

                    @Override
                    public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
                        return bean;
                    }
                });
            }
        });
        // 刷新IoC容器
        root.refresh();

        // Web 容器
        XmlWebApplicationContext wac = new XmlWebApplicationContext();
        wac.getEnvironment().addActiveProfile("wacProfile1");
        wac.setParent(root);
        wac.setServletContext(sc);
        wac.setNamespace("test-servlet");
        wac.setConfigLocations(new String[]{"/org/springframework/web/context/WEB-INF/test-servlet.xml"});
        // xml 转换成 Bean 的定义
        wac.refresh();
        return wac;
    }

    /**
     * 环境的合并
     */
    @Test
    @SuppressWarnings("deprecation")
    public void environmentMerge() {
        assertThat(this.root.getEnvironment().acceptsProfiles("rootProfile1")).isTrue();
        assertThat(this.root.getEnvironment().acceptsProfiles("wacProfile1")).isFalse();
        // applicationContext = createContext();
        assertThat(this.applicationContext.getEnvironment().acceptsProfiles("rootProfile1")).isTrue();
        assertThat(this.applicationContext.getEnvironment().acceptsProfiles("wacProfile1")).isTrue();
    }

    /**
     * Overridden as we can't trust superclass method
     *
     * @see org.springframework.context.AbstractApplicationContextTests#testEvents()
     */
    @Override
    protected void doTestEvents(TestListener listener, TestListener parentListener,
                                MyEvent event) {
        TestListener listenerBean = (TestListener) this.applicationContext.getBean("testListener");
        TestListener parentListenerBean = (TestListener) this.applicationContext.getParent().getBean("parentListener");
        super.doTestEvents(listenerBean, parentListenerBean, event);
    }

    /**
     * bean 的数量,Bean的定义看:
     * /org/springframework/web/context/WEB-INF/test-servlet.xml
     */
    @Test
    @Override
    public void count() {
        assertThat(this.applicationContext.getBeanDefinitionCount() == 14).as("should have 14 beans, not " + this.applicationContext.getBeanDefinitionCount()).isTrue();
    }

    @Test
    @SuppressWarnings("resource")
    public void withoutMessageSource() throws Exception {
        MockServletContext sc = new MockServletContext("");
        XmlWebApplicationContext wac = new XmlWebApplicationContext();
        wac.setParent(root);
        wac.setServletContext(sc);
        wac.setNamespace("testNamespace");
        wac.setConfigLocations(new String[]{"/org/springframework/web/context/WEB-INF/test-servlet.xml"});
        wac.refresh();
        assertThatExceptionOfType(NoSuchMessageException.class).isThrownBy(() ->
                wac.getMessage("someMessage", null, Locale.getDefault()));
        String msg = wac.getMessage("someMessage", null, "default", Locale.getDefault());
        assertThat("default".equals(msg)).as("Default message returned").isTrue();
    }


    @Test
    public void contextNesting() {
        // father 定义在 org/springframework/web/context/WEB-INF/contextInclude.xml 在Root Context 中定义
        TestBean father = (TestBean) this.applicationContext.getBean("father");
        // 为会笑以 debug father name 和定义不一样?
        assertThat(father != null).as("Bean from root context").isTrue();
        // Bean 的后置处理器对 TestBean 的处理,root 下的容器添加了
        assertThat(father.getFriends().contains("myFriend")).as("Custom BeanPostProcessor applied").isTrue();

        // get from child IoC context
        TestBean rod = (TestBean) this.applicationContext.getBean("rod");
        assertThat("Rod".equals(rod.getName())).as("Bean from child context").isTrue();
        assertThat(rod.getSpouse() == father).as("Bean has external reference").isTrue();
        // web IoC Context 并没有添加Bean后置处理器
        assertThat(!rod.getFriends().contains("myFriend")).as("Custom BeanPostProcessor not applied").isTrue();

        // parent rod Bean 被子 rod 这个Bean 覆盖了.
        rod = (TestBean) this.root.getBean("rod");
        // Web IoC 中的 rod
        assertThat("Roderick".equals(rod.getName())).as("Bean from root context").isTrue();
        // 但是后置处理器还是生效了.
        assertThat(rod.getFriends().contains("myFriend")).as("Custom BeanPostProcessor applied").isTrue();
    }

    @Test
    public void initializingBeanAndInitMethod() throws Exception {
        // {@see org.springframework.web.context.XmlWebApplicationContextTests$InitAndIB}
        assertThat(InitAndIB.constructed).isFalse();

        InitAndIB iib = (InitAndIB) this.applicationContext.getBean("init-and-ib");

        // 已经加载
        assertThat(InitAndIB.constructed).isTrue();

        // 判断这两个.init
        assertThat(iib.afterPropertiesSetInvoked && iib.initMethodInvoked).isTrue();
        assertThat(!iib.destroyed && !iib.customDestroyed).isTrue();

        // close web context ?? only parent close Bean才会被关闭??
        this.applicationContext.close();

        assertThat(!iib.destroyed && !iib.customDestroyed).isTrue();

        // 在当前中 parent 就是 root
        ConfigurableApplicationContext parent = (ConfigurableApplicationContext) this.applicationContext.getParent();
        parent.close();
        assertThat(iib.destroyed && iib.customDestroyed).isTrue();
        parent.close();
        assertThat(iib.destroyed && iib.customDestroyed).isTrue();

    }


    public static class InitAndIB implements InitializingBean, DisposableBean {

        public static boolean constructed;

        public boolean afterPropertiesSetInvoked, initMethodInvoked, destroyed, customDestroyed;

        public InitAndIB() {
            constructed = true;
        }

        @Override
        public void afterPropertiesSet() {
            assertThat(this.initMethodInvoked).isFalse();
            this.afterPropertiesSetInvoked = true;
        }

        /**
         * Init method
         */
        public void customInit() throws ServletException {
            assertThat(this.afterPropertiesSetInvoked).isTrue();
            this.initMethodInvoked = true;
        }

        @Override
        public void destroy() {
            assertThat(this.customDestroyed).isFalse();
            Assert.state(!this.destroyed, "Already destroyed");
            this.destroyed = true;
        }

        public void customDestroy() {
            assertThat(this.destroyed).isTrue();
            Assert.state(!this.customDestroyed, "Already customDestroyed");
            this.customDestroyed = true;
        }
    }

}
