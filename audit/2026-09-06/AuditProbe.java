import com.iefan.readout.tts.*;
import com.iefan.readout.utils.TextCleaner;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
public class AuditProbe {
 public static void main(String[] args) {
  for (String text : new String[]{"Hello world. Second sentence.", "你好。世界！再见？", "यह पहला वाक्य है। यह दूसरा वाक्य है।", "word ".repeat(1200)}) {
   try { var sentences = DocumentParser.INSTANCE.parse(text); System.out.println("parse chars="+text.length()+" sentences="+sentences.size()+" longest="+sentences.stream().mapToInt(s->s.getText().length()).max().orElse(0)); }
   catch(Throwable t) { System.out.println("parse chars="+text.length()+" ERROR="+t.getClass().getSimpleName()); }
  }
  String xml="<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t xml:space='preserve'>Hello </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p></w:body></w:document>";
  var doc=Jsoup.parse(xml,"",Parser.xmlParser()); var out=new StringBuilder();
  for(var p:doc.select("*|p")) for(var t:p.select("*|t")) out.append(t.text());
  System.out.println("DOCX split runs: ["+out+"]");
  System.out.println("Spanish normalization: "+SpokenTextNormalizer.INSTANCE.normalizeForSpeech("El descuento es 15%."));
  System.out.println("Time normalization: "+SpokenTextNormalizer.INSTANCE.normalizeForSpeech("Meet at 5:30 p.m."));
  System.out.println("Numeric content cleanup: ["+TextCleaner.INSTANCE.clean("Year\n2026\nValue\n42\nEnd")+"]");
 }
}
