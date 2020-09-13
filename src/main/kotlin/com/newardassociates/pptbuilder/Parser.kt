package com.newardassociates.pptbuilder

import com.vladsch.flexmark.util.data.MutableDataSet
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.util.*
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathExpression
import javax.xml.xpath.XPathFactory
import com.vladsch.flexmark.parser.Parser as MDParser
import org.w3c.dom.Document as XMLDocument
import org.w3c.dom.NodeList as XMLNodeList

class Parser(val properties : Properties) {
    private val logger = Logger.getLogger(Parser::class.java.canonicalName)

    fun parse(file : File) : Presentation {
        logger.info("Parsing ${file.absoluteFile}")
        return parse(factory.newDocumentBuilder().parse(file))
    }
    fun parse(contents : String) : Presentation {
        logger.info("Parsing string contents")
        return parse(factory.newDocumentBuilder().parse(InputSource(StringReader(contents))))
    }

    /* XML Machinery */
    private val factory = DocumentBuilderFactory.newInstance()
    init {
        factory.isNamespaceAware = true
        factory.isXIncludeAware = true
    }

    // Let's be honest, the use of XPath here is a little gratuitous, since these are
    // all fairly closely-clustered together and could've just as easily been navigated
    // by hand. But this way is a little bit clearer, and I argue more maintainable, even
    // if XPath is historically not highly-performant.
    private val xpath: XPath = XPathFactory.newInstance().newXPath()
    private val titleXPath = xpath.compile("/presentation/head/title/text()")
    private val abstractXPath = xpath.compile("/presentation/head/abstract/text()")
    private val audienceXPath = xpath.compile("/presentation/head/audience/text()")
    private val authorXPath = xpath.compile("/presentation/head/author/name/text()")
    private val affiliationXPath = xpath.compile("/presentation/head/author/affiliation/text()")
    private val jobTitleXPath = xpath.compile("/presentation/head/author/title/text()")
    private val emailXPath = xpath.compile("/presentation/head/author/contact/email")
    private val blogXPath = xpath.compile("/presentation/head/author/contact/blog")
    private val twitterXPath = xpath.compile("/presentation/head/author/contact/twitter")
    private val githubXPath = xpath.compile("/presentation/head/author/contact/github")
    private val linkedinXPath = xpath.compile("/presentation/head/author/contact/linkedin")
    private val categoryXPath = xpath.compile("/presentation/head/category")
    private val slideNotesXPath = xpath.compile("./notes")

    private fun parse(doc: XMLDocument): Presentation {
        check(doc.documentElement.tagName == "presentation")
        logger.info("Parsing Document instance w/root element ${doc.documentElement.tagName}")

        // Talk-specific bits
        val title = titleXPath.evaluate(doc, XPathConstants.STRING).toString()
        val abstract = abstractXPath.evaluate(doc, XPathConstants.STRING).toString()
        val audience = audienceXPath.evaluate(doc, XPathConstants.STRING).toString()

        val categories = categoryXPath.evaluate(doc, XPathConstants.NODESET) as XMLNodeList
        val keywords = mutableListOf<String>()
        if (categories.length > 0) {
            // pull out the categories for keywords in the doc
            for (n in 0..categories.length - 1) {
                val node = categories.item(n)
                keywords.add(node.textContent)
            }
        }

        // Author-specific bits
        var author = authorXPath.evaluate(doc, XPathConstants.STRING).toString()
        author = if (author == "") (properties["author"].toString()) else author

        fun getXMLOrPropertiesValue(xPath: XPathExpression, propName: String): String {
            if (xPath.evaluate(doc, XPathConstants.STRING) != null)
                return xPath.evaluate(doc, XPathConstants.STRING).toString()
            else if (properties[propName] != null)
                return properties[propName].toString()
            return ""
        }

        val affiliation = getXMLOrPropertiesValue(affiliationXPath, "affiliation")
        val jobTitle = getXMLOrPropertiesValue(jobTitleXPath, "jobTitle")
        logger.info("Author bits: ${author}|${affiliation}|${jobTitle}")

        val contactInfo = mutableMapOf<String, String>()
        contactInfo["email"] = getXMLOrPropertiesValue(emailXPath, "contact.email")
        contactInfo["blog"] = getXMLOrPropertiesValue(blogXPath, "contact.blog")
        contactInfo["twitter"] = getXMLOrPropertiesValue(twitterXPath, "contact.twitter")
        contactInfo["github"] = getXMLOrPropertiesValue(githubXPath, "contact.github")
        contactInfo["linkedin"] = getXMLOrPropertiesValue(linkedinXPath, "contact.linkedin")

        return Presentation(title, abstract, audience,
                author, jobTitle, affiliation,
                contactInfo, keywords,
                parseNodes(doc.documentElement.childNodes))
    }
    private fun parseNodes(nodes : XMLNodeList): List<Node> {
        val ast = mutableListOf<Node>()
        val mdParserOptions = MutableDataSet()
        val mdParser = MDParser.builder(mdParserOptions).build()

        var sectionTitle = ""

        for (i in 0 until nodes.length) {
            val node = nodes.item(i)

            when (node.nodeName) {
                // At some point, make "title" optional and default to current "section" title
                "slide" -> {
                    val titleNode = node.attributes.getNamedItem("title")
                    val title = if (titleNode != null) titleNode.nodeValue else sectionTitle
                    logger.info("Parsing slide title=${title}")

                    val notesNode = slideNotesXPath.evaluate(node, XPathConstants.NODESET) as XMLNodeList?
                    val notesList = mutableListOf<String>()
                    if (notesNode != null && notesNode.length > 0) {
                        for (n in 0..notesNode.length-1) {
                            val notesNodeChild = notesNode.item(n)
                            notesList.add(notesNodeChild.textContent)
                            node.removeChild(notesNodeChild)
                        }
                    }

                    ast.add(Slide(title, mdParser.parse(node.textContent.trimIndent()), node.textContent, notesList))
                }
                "section" -> {
                    val attrsMap = node.attributes
                    sectionTitle = attrsMap.getNamedItem("title").nodeValue
                    logger.info("Parsing section title=${sectionTitle}")
                    val sectionSubtitle = attrsMap.getNamedItem("subtitle")?.nodeValue
                    val sectionQuote = attrsMap.getNamedItem("quote")?.nodeValue
                    val sectionAttribution = attrsMap.getNamedItem("attribution")?.nodeValue
                    ast.add(Section(sectionTitle, sectionSubtitle, sectionQuote, sectionAttribution))
                }
                "slideset" -> {
                    logger.info("Parsing slideset")
                    ast.addAll(parseNodes(node.childNodes))
                    logger.info("End slideset")
                }
                // "head" gets ignored
                //else -> print("Ignoring ${node.nodeName}")
            }
        }
        return ast
    }
}
