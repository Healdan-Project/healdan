package com.logicaldoc.util.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class XMLDecoderGuardTest {

	private static final Set<String> ALLOWED = Set.of(Bean.class.getName(), ArrayList.class.getName(),
			Date.class.getName());

	private static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><java version=\"21\" class=\"java.beans.XMLDecoder\">";

	public static class Bean implements Serializable {
		private static final long serialVersionUID = 1L;

		private String name;

		private List<Date> dates = new ArrayList<>();

		private boolean enabled;

		private boolean visible;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public List<Date> getDates() {
			return dates;
		}

		public void setDates(List<Date> dates) {
			this.dates = dates;
		}

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public boolean isVisible() {
			return visible;
		}

		public void setVisible(boolean visible) {
			this.visible = visible;
		}
	}

	@Test
	public void testAcceptsWhatXMLEncoderWrites() {
		Bean bean = new Bean();
		bean.setName("a\u0000<b>&\"c\"");
		bean.getDates().add(new Date(0));
		bean.setEnabled(true);
		bean.setVisible(true);

		// Shared values make the encoder reference them through getters
		String xml = IOUtil.serialize(bean, null);
		XMLDecoderGuard.check(xml, ALLOWED);

		Bean read = (Bean) IOUtil.deserialize(xml);
		assertEquals(bean.getName(), read.getName());
		assertEquals(bean.getDates(), read.getDates());
	}

	@Test
	public void testAcceptsHandWrittenBean() {
		// Same header as the rejected documents below
		XMLDecoderGuard.check(HEADER + "<object class=\"" + Bean.class.getName() + "\" id=\"Bean0\">"
				+ "<void property=\"name\"><string>x</string></void><void property=\"dates\"><void method=\"add\">"
				+ "<object class=\"java.util.Date\"><long>0</long></object></void></void>"
				+ "<void method=\"isEnabled\" id=\"Boolean0\"/></object></java>", ALLOWED);
	}

	@Test
	public void testRejectsClassesNotAllowed() {
		assertRejected("<object class=\"java.lang.ProcessBuilder\"><array class=\"java.lang.String\" length=\"1\">"
				+ "<void index=\"0\"><string>id</string></void></array><void method=\"start\"/></object>");
		assertRejected("<object class=\"" + Bean.class.getName() + "\"><void property=\"dates\">"
				+ "<void method=\"add\"><object class=\"java.io.File\"><string>/tmp</string></object></void>"
				+ "</void></object>");
	}

	@Test
	public void testRejectsMethodsNotAllowed() {
		// Static methods on an allowed class
		assertRejected("<object class=\"java.util.Date\" method=\"parse\"><string>x</string></object>");
		assertRejected("<object class=\"java.util.Date\" method=\"getTime\"/>");

		// A way out to java.lang.Class and from there to any class
		assertRejected("<object class=\"" + Bean.class.getName() + "\"><void method=\"getClass\">"
				+ "<void method=\"forName\"><string>java.lang.Runtime</string></void></void></object>");
		assertRejected("<object class=\"" + Bean.class.getName() + "\"><void property=\"class\"/></object>");
	}

	@Test
	public void testRejectsOtherConstructs() {
		assertRejected("<class>java.lang.Runtime</class>");
		assertRejected("<object class=\"java.util.Date\" field=\"x\"/>");
		assertRejected("<new class=\"java.util.Date\"/>");
		assertRejected("<method name=\"getRuntime\" class=\"java.lang.Runtime\"/>");
		assertRejected("<property name=\"name\"/>");
		assertRejected("<field name=\"out\" class=\"java.lang.System\"/>");
		assertRejected("<var idref=\"x\"/>");
	}

	@Test
	public void testRejectsAnotherOwner() {
		assertThrows(IllegalArgumentException.class, () -> XMLDecoderGuard.check(
				"<?xml version=\"1.0\"?><java class=\"java.lang.Runtime\"><null/></java>", ALLOWED));
	}

	@Test
	public void testRejectsDoctype() {
		assertThrows(IllegalArgumentException.class,
				() -> XMLDecoderGuard.check("<?xml version=\"1.0\"?><!DOCTYPE java [<!ENTITY e \"x\">]>"
						+ "<java class=\"java.beans.XMLDecoder\"><string>&e;</string></java>", ALLOWED));
	}

	@Test
	public void testRejectsMalformedXml() {
		assertThrows(IllegalArgumentException.class, () -> XMLDecoderGuard.check(HEADER + "<object", ALLOWED));
	}

	private static void assertRejected(String body) {
		String xml = HEADER + body + "</java>";
		assertThrows(xml, IllegalArgumentException.class, () -> XMLDecoderGuard.check(xml, ALLOWED));
	}
}
