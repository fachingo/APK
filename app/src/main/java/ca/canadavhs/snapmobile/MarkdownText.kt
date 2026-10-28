package ca.canadavhs.snapmobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import org.commonmark.node.*
import org.commonmark.parser.Parser

/** CommonMark AST rendered as native text. HTML is inert and remote images are never loaded. */
@Composable internal fun MarkdownText(value:String) {
    val tree=remember(value) {Parser.builder().build().parse(value.take(100000))}
    val codeColor=MaterialTheme.colorScheme.surfaceVariant
    fun inline(root:Node):AnnotatedString=buildAnnotatedString {
        fun visit(node:Node,depth:Int=0) {
            if(depth>32) {append("[…]");return}
            when(node) {
                is org.commonmark.node.Text ->append(node.literal)
                is Code ->withStyle(SpanStyle(fontFamily=FontFamily.Monospace,background=codeColor)) {append(node.literal)}
                is StrongEmphasis ->withStyle(SpanStyle(fontWeight=FontWeight.Bold)) {var child=node.firstChild;while(child!=null) {visit(child,depth+1);child=child.next}}
                is Emphasis ->withStyle(SpanStyle(fontStyle=FontStyle.Italic)) {var child=node.firstChild;while(child!=null) {visit(child,depth+1);child=child.next}}
                is Link ->if(runCatching {java.net.URI(node.destination).scheme in listOf("https","http")}.getOrDefault(false)) withLink(LinkAnnotation.Url(node.destination)) {var child=node.firstChild;while(child!=null) {visit(child,depth+1);child=child.next}} else {var child=node.firstChild;while(child!=null) {visit(child,depth+1);child=child.next}}
                is Image -> {append("[Image: ");var child=node.firstChild;while(child!=null) {visit(child,depth+1);child=child.next};append("]")}
                is SoftLineBreak,is HardLineBreak ->append('\n')
                is HtmlInline ->append(node.literal)
                else -> {var child=node.firstChild;while(child!=null) {visit(child,depth+1);child=child.next}}
            }
        };visit(root)
    }
    // Keep AST traversal separate from layout, with one immutable entry per block.
    data class Block(val node:Node,val indent:Int,val bullet:Boolean=false)
    val blocks=remember(tree) {buildList<Block> {
        fun walk(node:Node,depth:Int=0,bullet:Boolean=false) {
            if(depth>32) return
            when(node) {
                is org.commonmark.node.Paragraph,is Heading,is FencedCodeBlock,is IndentedCodeBlock,is HtmlBlock,is ThematicBreak ->add(Block(node,depth.coerceAtMost(6),bullet))
                else -> {var child=node.firstChild;var first=true
                    while(child!=null) {val current=child;walk(current,if(node is ListItem || node is BlockQuote) depth+1 else depth,node is ListItem && first);first=false;child=current.next}
                }
            }
        };walk(tree)
    }}
    SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {blocks.forEach {block ->
        val node=block.node
        Row(Modifier.padding(start=(block.indent*8).dp)) {
            if(block.bullet) Text("• ")
            when(node) {
                is org.commonmark.node.Paragraph ->Text(inline(node))
                is Heading ->Text(inline(node),style=if(node.level<=2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                is FencedCodeBlock ->Surface(color=codeColor) {Text(node.literal,Modifier.padding(10.dp),fontFamily=FontFamily.Monospace)}
                is IndentedCodeBlock ->Text(node.literal,fontFamily=FontFamily.Monospace)
                is HtmlBlock ->Text(node.literal)
                is ThematicBreak ->HorizontalDivider()
                else ->Unit
            }
        }
    }}}
}
