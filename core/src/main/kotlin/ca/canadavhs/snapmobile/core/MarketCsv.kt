package ca.canadavhs.snapmobile.core

import java.io.Reader
import java.io.PushbackReader

/** RFC4180 streaming reader: quoted commas/newlines, escaped quotes, BOM, no whole-file load. */
class MarketCsv(reader:Reader):AutoCloseable {
    private val input=PushbackReader(reader,2);private var first=true
    fun next():List<String>? {
        val result=mutableListOf<String>();val field=StringBuilder();var quoted=false;var started=false;var closed=false
        fun add() {require(result.size<100) {"Too many CSV columns"};result+=field.toString();field.setLength(0);closed=false}
        while(true) {
            var code=input.read()
            if(first) {first=false;if(code==0xfeff) code=input.read()}
            if(code<0) {require(!quoted) {"Unclosed CSV quotation"};if(!started && field.isEmpty() && result.isEmpty()) return null;add();return result}
            started=true;val c=code.toChar();require(field.length<200_000) {"CSV field exceeds limit"}
            if(quoted) {
                if(c=='"') {val n=input.read();if(n=='"'.code) field.append('"') else {quoted=false;closed=true;if(n>=0) input.unread(n)}} else field.append(c)
            } else when(c) {
                '"'->{require(field.isEmpty() && !closed) {"Unexpected CSV quotation"};quoted=true}
                ','->add()
                '\n'->{add();return result}
                '\r'->{val n=input.read();if(n>=0 && n!='\n'.code) input.unread(n);add();return result}
                else->{require(!closed) {"Unexpected text after CSV quotation"};field.append(c)}
            }
        }
    }
    override fun close()=input.close()
}
