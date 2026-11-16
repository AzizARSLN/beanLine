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
            "select lt.adi as tetkikAdi, hm.kodu as makroKodu, t.sonuc from LisHastaTupTetkik t " +
                "left join LisTetkik lt on (lt=t.lisTetkik) " +
                "left join HizmetMakro hm on (hm=lt.hizmetMakro) " +
                "left join LisTupTetkik lht on (lt=lht.lisTetkik) " +
                "where t.hastaHizmet.birimSevk.id in :p0",
            conversion.jpql,
        )
        assertTrue(conversion.notes.toString(), conversion.notes.isEmpty())
    }

    @Test
    fun `ignores calls nested inside arguments`() {
        val conversion = QueryBuilderToJpql.convert(
            """
            QueryBuilder.create(Order.class, "o", em)
                .join(Customer.class, "c", "c=o.customer", JoinType.LEFT)
                .where("c.id")
                .orderBy("o.createdAt")
            """.trimIndent(),
        )!!

        assertEquals(
            "select o from Order o left join Customer c on (c=o.customer) where c.id = :p0 order by o.createdAt",
            conversion.jpql,
        )
        assertTrue(conversion.notes.isEmpty())
    }

    @Test
    fun `ignores text that is not a QueryBuilder chain`() {
        assertNull(QueryBuilderToJpql.convert("select o from Order o where o.id = :id"))
    }
}
