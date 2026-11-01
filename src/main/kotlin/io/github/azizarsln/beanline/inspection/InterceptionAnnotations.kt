package io.github.azizarsln.beanline.inspection

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiMethod

const val SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional"

val EJB_TRANSACTION_ATTRIBUTES = listOf(
    "javax.ejb.TransactionAttribute",
    "jakarta.ejb.TransactionAttribute",
)

val TRANSACTION_ANNOTATIONS: List<String> = EJB_TRANSACTION_ATTRIBUTES + listOf(
    SPRING_TRANSACTIONAL,
    "javax.transaction.Transactional",
    "jakarta.transaction.Transactional",
)

val BEHAVIOR_ANNOTATIONS = listOf(
    "javax.ejb.Asynchronous",
    "jakarta.ejb.Asynchronous",
    "org.springframework.scheduling.annotation.Async",
    "org.springframework.cache.annotation.Cacheable",
    "org.springframework.cache.annotation.CachePut",
    "org.springframework.cache.annotation.CacheEvict",
    "org.springframework.retry.annotation.Retryable",
)

val INTERCEPTION_ANNOTATIONS: List<String> = TRANSACTION_ANNOTATIONS + BEHAVIOR_ANNOTATIONS

fun PsiMethod.findOwnAnnotation(names: List<String>): PsiAnnotation? = names.firstNotNullOfOrNull(::getAnnotation)

val PsiAnnotation.shortName: String
    get() = qualifiedName.orEmpty().substringAfterLast('.')
