package io.github.azizarsln.beanline.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryBuilderToJpqlTest {

    @Test
    fun `converts a chain with aliases, joins and whereIn`() {
        val conversion = QueryBuilderToJpql.convert(
            """
            QueryBuilder.create(LisHastaTupTetkik.class, "t", em)
                .selectAlias("lt.adi", "tetkikAdi")
                .selectAlias("hm.kodu", "makroKodu")
                .select("sonuc")
                .join(LisTetkik.class, "lt", "lt=t.lisTetkik", JoinType.LEFT)
                .join(HizmetMakro.class, "hm", "hm=lt.hizmetMakro", JoinType.LEFT)
                .join(LisTupTetkik.class, "lht", "lt=lht.lisTetkik", JoinType.LEFT)
                .whereIn("t.hastaHizmet.birimSevk.id", birimSevk.getId())
            """.trimIndent(),
        )!!

        assertEquals(
            "select lt.adi as tetkikAdi, hm.kodu as makroKodu, sonuc from LisHastaTupTetkik t " +
                "left join t.lisTetkik lt left join lt.hizmetMakro hm left join lht.lisTetkik lht " +
                "where t.hastaHizmet.birimSevk.id in (:p1)",
            conversion.jpql,
        )
        assertTrue(conversion.notes.toString(), conversion.notes.any { it.contains("names 'lt'") })
        assertTrue(conversion.notes.toString(), conversion.notes.any { it.contains("alias being joined") })
    }

    @Test
    fun `ignores calls nested inside arguments`() {
        val conversion = QueryBuilderToJpql.convert(
            """
            QueryBuilder.create(Order.class, "o", em)
                .join(Customer.class, "c", "c=o.customer", JoinType.LEFT)
                .where("c.id = :customerId")
                .orderBy("o.createdAt")
            """.trimIndent(),
        )!!

        assertEquals(
            "select o from Order o left join o.customer c where c.id = :customerId order by o.createdAt",
            conversion.jpql,
        )
        assertTrue(conversion.notes.isEmpty())
    }

    @Test
    fun `ignores text that is not a QueryBuilder chain`() {
        assertNull(QueryBuilderToJpql.convert("select o from Order o where o.id = :id"))
    }
}
