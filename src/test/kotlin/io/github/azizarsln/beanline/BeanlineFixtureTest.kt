package io.github.azizarsln.beanline

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import io.github.azizarsln.beanline.bean.findCandidateBeans
import io.github.azizarsln.beanline.bean.findInjectionPoints
import io.github.azizarsln.beanline.endpoint.EndpointIndex
import io.github.azizarsln.beanline.inspection.ManagedBeanNewInspection
import io.github.azizarsln.beanline.inspection.SelfInvocationInspection
import io.github.azizarsln.beanline.inspection.UninterceptableMethodInspection

class BeanlineFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = object : DefaultLightProjectDescriptor() {
        override fun getSdk(): Sdk = JavaSdk.getInstance().createJdk("test-jdk", System.getProperty("java.home"), false)
    }

    private fun annotation(fqn: String, body: String = "") {
        val packageName = fqn.substringBeforeLast('.')
        val name = fqn.substringAfterLast('.')
        myFixture.addClass("package $packageName; public @interface $name { $body }")
    }

    private fun addEjbStubs() {
        annotation("jakarta.ejb.Stateless")
        annotation("jakarta.ejb.EJB")
        annotation("jakarta.ejb.Asynchronous")
        myFixture.addClass("package jakarta.ejb; public enum TransactionAttributeType { REQUIRED, REQUIRES_NEW, NEVER }")
        annotation("jakarta.ejb.TransactionAttribute", "TransactionAttributeType value() default TransactionAttributeType.REQUIRED;")
    }

    private fun warnings(inspection: LocalInspectionTool, source: String): List<String> {
        myFixture.enableInspections(inspection)
        myFixture.configureByText("Subject.java", source)
        return myFixture.doHighlighting(HighlightSeverity.WARNING)
            .mapNotNull { it.description }
            .filter { it.contains("proxy") || it.contains("managed bean") }
    }

    fun testSelfInvocationReportsRequiresNew() {
        addEjbStubs()
        val found = warnings(SelfInvocationInspection(), """
            import jakarta.ejb.*;
            @Stateless public class Subject {
                public void a() { b(); this.b(); }
                @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW) public void b() {}
            }
        """.trimIndent())
        assertEquals(found.toString(), 2, found.size)
        assertTrue(found[0].contains("@TransactionAttribute on 'b'"))
    }

    fun testSelfInvocationIgnoresDefaultRequiredAndEqualSettings() {
        addEjbStubs()
        val found = warnings(SelfInvocationInspection(), """
            import jakarta.ejb.*;
            @Stateless public class Subject {
                public void a() { b(); }
                @TransactionAttribute(TransactionAttributeType.REQUIRED) public void b() {}
                @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW) public void c() { d(); }
                @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW) public void d() {}
            }
        """.trimIndent())
        assertEmpty(found)
    }

    fun testSelfInvocationIgnoresCallsThroughAnotherReference() {
        addEjbStubs()
        val found = warnings(SelfInvocationInspection(), """
            import jakarta.ejb.*;
            @Stateless public class Subject {
                @EJB Subject self;
                public void a() { self.b(); }
                @Asynchronous public void b() {}
            }
        """.trimIndent())
        assertEmpty(found)
    }

    fun testSelfInvocationIgnoresClassesThatAreNotBeans() {
        addEjbStubs()
        val found = warnings(SelfInvocationInspection(), """
            import jakarta.ejb.*;
            public class Subject {
                public void a() { b(); }
                @Asynchronous public void b() {}
            }
        """.trimIndent())
        assertEmpty(found)
    }

    fun testSelfInvocationReportsCallInsideLambda() {
        addEjbStubs()
        val found = warnings(SelfInvocationInspection(), """
            import jakarta.ejb.*;
            @Stateless public class Subject {
                public void a() { Runnable r = () -> b(); }
                @Asynchronous public void b() {}
            }
        """.trimIndent())
        assertEquals(found.toString(), 1, found.size)
    }

    fun testUninterceptableMethodFixMakesPrivateMethodPublic() {
        addEjbStubs()
        val found = warnings(UninterceptableMethodInspection(), """
            import jakarta.ejb.*;
            @Stateless public class Subject {
                @Asynchronous private void <caret>a() {}
                @Asynchronous public void b() {}
            }
        """.trimIndent())
        assertEquals(found.toString(), 1, found.size)
        myFixture.launchAction(myFixture.findSingleIntention("Make method public and non-final"))
        val method = (myFixture.file as PsiJavaFile).classes[0].findMethodsByName("a", false)[0]
        assertTrue(myFixture.file.text, method.hasModifierProperty(PsiModifier.PUBLIC))
        assertFalse(myFixture.file.text, method.hasModifierProperty(PsiModifier.PRIVATE))
    }

    fun testManagedBeanNewReportsOnlyBeansWithInjectedFields() {
        addEjbStubs()
        myFixture.addClass("import jakarta.ejb.*; @Stateless public class WithInjection { @EJB Object dependency; }")
        myFixture.addClass("import jakarta.ejb.*; @Stateless public class WithoutInjection { }")
        val found = warnings(ManagedBeanNewInspection(), """
            public class Subject {
                Object a = new WithInjection();
                Object b = new WithoutInjection();
            }
        """.trimIndent())
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found[0].contains("'dependency'"))
    }

    fun testEndpointIndexFindsJaxRsIncludingInheritedMethods() {
        annotation("jakarta.ws.rs.Path", "String value();")
        annotation("jakarta.ws.rs.GET")
        annotation("jakarta.ws.rs.POST")
        myFixture.addClass("""
            import jakarta.ws.rs.*;
            public abstract class BaseResource { @GET @Path("/list") public String list() { return ""; } }
        """.trimIndent())
        myFixture.addClass("""
            import jakarta.ws.rs.*;
            @Path("/orders") public class OrderResource extends BaseResource {
                private static final String ITEM = "/{id}";
                @POST @Path(ITEM) public String save() { return ""; }
                public String helper() { return ""; }
            }
        """.trimIndent())

        val endpoints = EndpointIndex.getInstance(project).all().associate { it.template to it.verbs }
        assertEquals(mapOf("/orders/list" to listOf("GET"), "/orders/{id}" to listOf("POST")), endpoints)
    }

    fun testEndpointIndexFindsSpringMappings() {
        annotation("org.springframework.web.bind.annotation.RestController")
        annotation("org.springframework.web.bind.annotation.GetMapping", "String[] value() default {};")
        annotation("org.springframework.web.bind.annotation.RequestMapping", "String[] value() default {}; RequestMethod[] method() default {};")
        myFixture.addClass("package org.springframework.web.bind.annotation; public enum RequestMethod { GET, POST }")
        myFixture.addClass("""
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/api") public class OrderController {
                @GetMapping({"/a", "/b"}) public String multi() { return ""; }
                @RequestMapping(value = "/c", method = RequestMethod.POST) public String post() { return ""; }
                @RequestMapping("/d") public String any() { return ""; }
            }
        """.trimIndent())

        val endpoints = EndpointIndex.getInstance(project).all().associate { it.template to it.verbs }
        assertEquals(
            mapOf(
                "/api/a" to listOf("GET"),
                "/api/b" to listOf("GET"),
                "/api/c" to listOf("POST"),
                "/api/d" to listOf("ANY"),
            ),
            endpoints,
        )
    }

    fun testBeanGraphNavigatesBothWaysThroughAnInterface() {
        addEjbStubs()
        myFixture.addClass("public interface Payments { }")
        val bean: PsiClass = myFixture.addClass("import jakarta.ejb.*; @Stateless public class CardPayments implements Payments { }")
        val consumer: PsiClass = myFixture.addClass("import jakarta.ejb.*; @Stateless public class Checkout { @EJB Payments payments; }")
        val scope = GlobalSearchScope.projectScope(project)

        val field: PsiField = consumer.findFieldByName("payments", false)!!
        assertEquals(listOf<Any>(field), findInjectionPoints(bean, scope))
        assertEquals(listOf(bean), findCandidateBeans(field.type, scope))
    }
}
