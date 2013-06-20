package org.picocontainer.web.sample.struts2.pwr.selenium;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.thoughtworks.selenium.Selenium;

public class ListAddCheese {

	@Test
	public void smokeTestForPromptPage() throws InterruptedException {
		Selenium selenium = SeleniumTestSuite.selenium();
		selenium.open("/struts2-webapp-with-remoting/cheeses.action");
		assertTrue(selenium.isTextPresent("Brie"));
		assertTrue(selenium.isTextPresent("France"));
		assertTrue(selenium.isElementPresent("css=input[type=\"submit\"]"));
		assertTrue(selenium.isElementPresent("name=count"));
		assertEquals("4", selenium.getValue("name=count"));
	}
	
	

}
