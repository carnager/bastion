package io.github.carnager.bastion

import io.github.carnager.bastion.data.Subnet
import io.github.carnager.bastion.data.currentPrivateSubnet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SubnetTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test fun matchesAddressesInTheNetwork() {
        val home = Subnet.parse("192.168.1.0/24")!!
        assertTrue(ip("192.168.1.10") in home)
        assertFalse(ip("192.168.2.10") in home)
        assertFalse(ip("10.10.0.2") in home)
        assertFalse(ip("fd00::1") in home)
        assertTrue(ip("fd00::1") in Subnet.parse("fd00::/8")!!)
        assertTrue(ip("10.1.2.3") in Subnet.parse("10.0.0.0/8")!!)
        assertTrue(ip("172.20.0.1") in Subnet.parse("172.16.0.0/12")!!)
        assertFalse(ip("172.32.0.1") in Subnet.parse("172.16.0.0/12")!!)
    }

    @Test fun parsesLeniently() {
        assertEquals("192.168.1.0/24", Subnet.parse(" 192.168.1.77/24 ").toString())
        assertEquals("192.168.1.5/32", Subnet.parse("192.168.1.5").toString())
        assertEquals("192.168.1.0/24", Subnet.of(ip("192.168.1.23"), 24).toString())
    }

    @Test fun rejectsWhatIsNotANetwork() {
        for (bad in listOf("", "home", "example.org/24", "192.168.1.0/33", "192.168.1.300/24", "1.2.3.4/x", "1.2.3.4/8/9"))
            assertNull(bad, Subnet.parse(bad))
    }

    @Test fun offersThePrivateNetworkThePhoneIsIn() {
        val mobileAndWifi = listOf(ip("100.64.3.4") to 10, ip("fe80::1") to 64, ip("192.168.1.23") to 24)
        assertEquals("192.168.1.0/24", currentPrivateSubnet(mobileAndWifi).toString())
        assertNull(currentPrivateSubnet(listOf(ip("100.64.3.4") to 10)))  // mobile data only
    }
}
