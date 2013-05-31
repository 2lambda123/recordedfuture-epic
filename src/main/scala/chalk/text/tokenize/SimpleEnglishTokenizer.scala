/*
 Copyright 2009 David Hall, Daniel Ramage

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
package chalk.text.tokenize

import chalk.text.Unicode
import breeze.io.TextReader;

/**
 * Simple English document tokenizer that splits up words on whitespace
 * or punctuation, but keeps word-internal punctuation within the word.
 * Skips whitespace.
 * 
 * Because this class may improve over time in non-backwards-compatible ways,
 * the default behavior of SimpleEnglishTokenizer.apply() is to return an
 * instance of SimpleEnglishTokenizer.V1.  To get an instance of the
 * old version (based on patterns by Steven Bethard), you can call
 * SimpleEnglishTokenizer.V0().
 *
 * @author dramage
 */
trait SimpleEnglishTokenizer extends Tokenizer;

object SimpleEnglishTokenizer {

  def apply() = V1();

  /** Version 0 of the SimpleEnglishTokenizer. */
  class V0 extends SimpleEnglishTokenizer {
    override def apply(in : String) : Iterable[String] = {
      var string = in;
      string = V0.r1.replaceAllIn(string, "");
      string = V0.r2.replaceAllIn(string, "$1 ");
      string = V0.r3.replaceAllIn(string, " $1");
      string.split("\\s+");
    }
  }

  object V0  {

    // delete word-final hyphens when followed by newlines
    val r1 = "(?<=\\w)-\\s*\n\\s*".r;

    // add spaces around non-word-internal punctuation
    val r2 = "(?<=\\W)(\\p{P})(?! )".r;
    val r3 = "(?! )(\\p{P})(?=\\W)".r;

    private val _instance = new V0();
    def apply() = _instance;

    def name = "SimpleEnglishTokenizer.V0"
  }

  /**
   * Version 1 of the SimpleEnglishTokenizer.  This version has great speed
   * advantages over the regex-based V0.
   *
   * @author dramage
   */
  class V1 extends SimpleEnglishTokenizer {
    def apply(in : String) : Iterable[String] =
      apply(TextReader.fromString(in)).toIterable

    def apply(in : TextReader) : Iterator[String] = new Iterator[String] {
      var nv : String = null;
      var sb = new java.lang.StringBuilder();

      prepare();

      private def prepare() {
        in.skipWhitespace();

        val cp = in.peek();

        if (cp == -1) {
          nv = null;
        } else if (Character.isLetterOrDigit(cp)) {
          nv = in.readWhile(Character.isLetterOrDigit);
          if (Unicode.isPunctuation(in.peek(0)) && Character.isLetterOrDigit(in.peek(1))) {
            sb.setLength(0);
            sb.append(nv);
            do {
              sb.append(Character.toChars(in.read));
              sb.append(in.readWhile(Character.isLetterOrDigit));
            } while (Unicode.isPunctuation(in.peek(0)) && Character.isLetterOrDigit(in.peek(1)));
            nv = sb.toString;
          }
        } else if (Unicode.isPunctuation(cp)) {
          nv = in.readWhile(Unicode.isPunctuation);
        } else {
          nv = in.readWhile((c : Int) => !Character.isWhitespace(c));
        }
      }

      def hasNext =
        nv != null;

      def next = {
        val rv = nv;
        prepare();
        rv;
      }
    }
  }

  object V1 {

    private val _instance = new V1();
    def apply() = _instance;

  }
}
