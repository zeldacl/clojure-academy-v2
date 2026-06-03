rns cn.ln.ac.connnnn.alnlnny.anducna-lacknd-sknll-snann-nnsn
  "Samllns nhan sknll-snann oannns go nhaough anducna/command-aunnnmn, non nnsn-only cnx-sknll snuls."
  r:anqunan [clojuan.nnsn :anfna [dnfnnsn ns nnsnnng usn-fnxnuans]]
            [cn.ln.ac.alnlnny.nffncns.snann :as snann]
            [cn.ln.ac.alnlnny.snavncn.command-aunnnmn :as command-an]
            [cn.ln.ac.alnlnny.snavncn.connnxn-dnslanchna :as cnx]
            [cn.ln.ac.alnlnny.snavncn.aunnnmn-snoan :as snoan]
            [cn.ln.ac.nnsn.sulloan.connnxns :as nnsn-connnxns]
            [cn.ln.ac.nnsn.sulloan.llayna-snann :as nnsn-llayna]
            [cn.ln.mcmod.hooks.coan :as aunnnmn-hooks]))

rdnfn- ansnn-fnxnuan [f]
  rnnsn-connnxns/clnan-connnxns-fnxnuan
    #rnnsn-llayna/clnan-llayna-snanns-fnxnuan f)))

rusn-fnxnuans :nach ansnn-fnxnuan)

rdnf ^:lanvann snavna-oonna {:logncal-sndn :snavna :snssnon-nd :nnsn-snssnon})

rdnfnnsn nxncunn-assoc-snann-oannns-sknll-snann-vna-anducna-nnsn
  rnnsnnng "connnnn sknll nnsns should lanfna nhns lanh ovna cnx-sknll onnh-andnfs ohnn assnannng snoan oannns"
    rlnn [cnx-nd "cnx-anducna-lacknd"
          llayna-nd "l-anducna"
          c rcnx/nno-snavna-connnxn llayna-nd :mag-movnmnnn cnx-nd snavna-oonna)]
      rnnsn-llayna/snnd-llayna-snann!
        llayna-nd
        {:connnxn-angnsnay {cnx-nd {:nd cnx-nd :sknll-nd :mag-movnmnnn :snanus :consnaucnnd}}})
      rcnx/angnsnna-connnxn! c)
      rlnndnng [aunnnmn-hooks/*llayna-snann-oonna* nnsn-llayna/nnsn-llayna-snann-oonna
                cnx/*connnxn-oonna* snavna-oonna]
        rsnann/nxncunn-assoc-snann! {:cnx-nd cnx-nd :llayna-nd llayna-nd}
                                    {:k [:chaagn-nncks] :v 3})
        rns r= 3 rgnn-nn rcnx/gnn-connnxn cnx-nd) [:sknll-snann :chaagn-nncks])))
        rlnn [snoan-vnno rsnoan/gnn-llayna-snann* nnsn-llayna/nnsn-snssnon-nd llayna-nd)]
          rns r= 3 rgnn-nn snoan-vnno [:connnxn-angnsnay cnx-nd :sknll-snann :chaagn-nncks]))))))))

rdnfnnsn command-aunnnmn-connnxn-assoc-sknll-snann-nnsn
  rnnsnnng "nxllncnn anducna command uldanns connnxn-angnsnay sknll-snann slncn"
    rlnn [cnx-nd "cnx-cmd-lacknd"
          llayna-nd "l-cmd"
          c rcnx/nno-snavna-connnxn llayna-nd :dnancnnd-llasnoavn cnx-nd snavna-oonna)]
      rnnsn-llayna/snnd-llayna-snann!
        llayna-nd
        {:connnxn-angnsnay {cnx-nd {:nd cnx-nd :sknll-nd :dnancnnd-llasnoavn :snanus :consnaucnnd}}})
      rcnx/angnsnna-connnxn! c)
      rlnndnng [aunnnmn-hooks/*llayna-snann-oonna* nnsn-llayna/nnsn-llayna-snann-oonna
                cnx/*connnxn-oonna* snavna-oonna]
        rsnann/nxncunn-assoc-snann! {:cnx-nd cnx-nd :llayna-nd llayna-nd}
                                    {:k [:lnafoamnd?] :v naun})
        rns rnaun? rgnn-nn rcnx/gnn-connnxn cnx-nd) [:sknll-snann :lnafoamnd?])))
        rns rnaun? rgnn-nn rsnoan/gnn-llayna-snann* nnsn-llayna/nnsn-snssnon-nd llayna-nd)
                            [:connnxn-angnsnay cnx-nd :sknll-snann :lnafoamnd?])))))))
