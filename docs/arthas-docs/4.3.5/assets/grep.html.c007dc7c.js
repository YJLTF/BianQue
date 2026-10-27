import{_ as e,o as n,c as i,d as s}from"./app.e2427bec.js";const l={},t=s(`<h1 id="grep" tabindex="-1"><a class="header-anchor" href="#grep" aria-hidden="true">#</a> grep</h1><div class="custom-container tip"><p class="custom-container-title">TIP</p><p>Similar to the traditional <code>grep</code> command.</p></div><h2 id="usage" tabindex="-1"><a class="header-anchor" href="#usage" aria-hidden="true">#</a> Usage</h2><div class="language-text ext-text line-numbers-mode"><pre class="language-text"><code> USAGE:
   grep [-A &lt;value&gt;] [-B &lt;value&gt;] [-C &lt;value&gt;] [-h] [-i] [-v] [-n] [-c] [-m &lt;value&gt;] [-e] [--trim-end] pattern

 SUMMARY:
   grep command for pipes.

 EXAMPLES:
  sysprop | grep java
  sysprop | grep java -n
  sysenv | grep -v JAVA
  sysenv | grep -e &quot;(?i)(JAVA|sun)&quot; -m 3  -C 2
  sysenv | grep JAVA -A2 -B3
  thread --all | grep -c HttpClient
  thread | grep -m 10 -e  &quot;TIMED_WAITING|WAITING&quot;

 WIKI:
   https://arthas.aliyun.com/doc/grep

 OPTIONS:
 -A, --after-context &lt;value&gt;                                                    Print NUM lines of trailing context)
 -B, --before-context &lt;value&gt;                                                   Print NUM lines of leading context)
 -C, --context &lt;value&gt;                                                          Print NUM lines of output context)
 -h, --help                                                                     this help
 -i, --ignore-case                                                              Perform case insensitive matching.  By default, grep is case sensitive.
 -v, --invert-match                                                             Select non-matching lines
 -n, --line-number                                                              Print line number with output lines
 -c, --count                                                                    Print only a count of selected lines
 -m, --max-count &lt;value&gt;                                                        stop after NUM selected lines)
 -e, --regex                                                                    Enable regular expression to match
     --trim-end                                                                 Remove whitespaces at the end of the line
 &lt;pattern&gt;                                                                      Pattern
</code></pre><div class="line-numbers" aria-hidden="true"><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div><div class="line-number"></div></div></div>`,4),a=[t];function r(d,c){return n(),i("div",null,a)}const u=e(l,[["render",r],["__file","grep.html.vue"]]);export{u as default};
