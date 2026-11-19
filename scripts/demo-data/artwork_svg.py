"""Original SVG teaching studies. Each seed retains its composition across revisions."""
import math
import random
from pathlib import Path
from PIL import Image


class Drawing:
    def __init__(self, seed, revision):
        self.rng=random.Random(seed); self.revision=revision; self.defs=[]; self.parts=[]

    def add(self, value): self.parts.append(value)

    def path(self, path, fill='none', stroke='none', width=1, opacity=1, extra=''):
        self.add(f'<path d="{path}" fill="{fill}" stroke="{stroke}" stroke-width="{width}" opacity="{opacity}" stroke-linecap="round" stroke-linejoin="round" {extra}/>')

    def ellipse(self,x,y,rx,ry,fill,opacity=1,extra=''):
        self.add(f'<ellipse cx="{x}" cy="{y}" rx="{rx}" ry="{ry}" fill="{fill}" opacity="{opacity}" {extra}/>')

    def gradient(self,name,stops,radial=False,extra=''):
        tag='radialGradient' if radial else 'linearGradient'
        self.defs.append(f'<{tag} id="{name}" {extra}>'+''.join(f'<stop offset="{o}%" stop-color="{c}"/>' for o,c in stops)+f'</{tag}>')
        return f'url(#{name})'

    def group(self,extra=''): self.add(f'<g {extra}>')
    def end(self): self.add('</g>')

    def hatch(self,path,name,strength=.12,spacing=7,cross=False):
        self.defs.append(f'<clipPath id="{name}"><path d="{path}"/></clipPath>')
        self.group(f'clip-path="url(#{name})"')
        for x in range(-1000,2300,spacing):
            self.path(f'M{x} 910 l500 -1000',stroke='#34322e',width=.8,opacity=strength)
        if cross:
            for x in range(-600,2000,spacing+4):self.path(f'M{x} 0 l700 950',stroke='#34322e',width=.65,opacity=strength*.55)
        self.end()

    def svg(self):
        return '<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="900" viewBox="0 0 1200 900"><defs>'+''.join(self.defs)+'</defs>'+''.join(self.parts)+'</svg>'


def paper(d,color='#f5f1e7'):
    d.add(f'<rect width="1200" height="900" fill="{color}"/>')
    r=random.Random(112)
    for _ in range(1700):
        x,y=r.uniform(0,1200),r.uniform(0,900)
        d.path(f'M{x:.1f} {y:.1f} l{r.uniform(.2,2):.1f} {r.uniform(-.4,.4):.1f}',stroke='#8a806e',width=.5,opacity=r.uniform(.025,.085))


def sketch(d,advanced=False):
    rev=d.revision;r=d.rng;paper(d,'#f1eee6')
    dx=r.randint(-50,45);dy=r.randint(-15,14)
    d.group(f'transform="translate({dx} {dy})"')
    wall=d.gradient('wall',[(0,'#f2f0e9'),(100,'#dbd7cf')],extra='x1="0" y1="0" x2="0" y2="1"')
    d.path('M110 118 H1080 V579 H110 Z',wall,opacity=.65)
    d.path('M70 581 Q580 535 1130 565',stroke='#827d71',opacity=.35)
    for i in range(6):d.ellipse(660-i*4,650+i*2,345-i*27,55-i*6,'#403c33',.023 if rev==1 else .037)
    for path in ['M108 731 Q253 639 340 619 Q248 697 320 809','M531 612 Q643 733 616 851 L693 820 Q679 687 598 611','M792 612 Q906 681 1040 693 L1034 727 Q880 706 792 612']:
        d.path(path,fill='#9a958a',opacity=.065 if rev==1 else .17)
        d.path(path,stroke='#6f6b62',width=1,opacity=.28)
    pot_width=r.uniform(.9,1.16);pot_height=r.uniform(.88,1.07)
    d.group(f'transform="translate(420 640) scale({pot_width} {pot_height}) translate(-420 -640)"')
    jug='M365 298 C363 335 352 373 325 418 C291 475 286 537 311 588 C340 640 462 661 515 615 C552 579 548 507 521 451 C499 406 490 360 493 302 Z'
    glaze=d.gradient('jug',[(0,'#ebe7dc'),(22,'#d3d0c6'),(49,'#a39e95'),(70,'#68665f'),(87,'#aaa69d'),(100,'#817c74')],extra='x1="0" y1="0" x2="1" y2=".15"')
    d.path('M488 361 C629 332 650 493 542 508 L539 480 C602 470 588 390 502 397 Z',fill='#928f87',stroke='#54524c',width=1.7,opacity=.78)
    d.path(jug,glaze,stroke='#57554e',width=1.6,opacity=.78 if rev==1 else 1)
    d.hatch(jug,'jugHatch',.04 if rev==1 else .14,8,rev>=3)
    d.ellipse(429,302,64,17,'#5e5c54',.6)
    d.path('M366 301 Q430 271 493 299 Q431 320 366 301',stroke='#4c4943',width=2)
    d.path('M371 306 Q420 323 484 307',stroke='#f0eee5',width=4,opacity=.65)
    d.path('M338 429 Q313 491 329 554',stroke='#efede6',width=7,opacity=.45 if rev>1 else .18)
    d.path('M491 408 C501 449 535 513 514 582 Q472 626 382 617',stroke='#3c3b36',width=2,opacity=.24 if rev==1 else .62)
    d.end()
    d.group(f'transform="translate({r.randint(-18,35)} {r.randint(-12,14)})"')
    front='M622 453 L795 480 L789 657 L614 615 Z';top='M622 453 L704 399 L878 427 L795 480 Z';side='M795 480 L878 427 L863 601 L789 657 Z'
    for name,p,colors in [('cubeFront',front,[(0,'#e6e3db'),(100,'#b8b5ac')]),('cubeTop',top,[(0,'#faf7ef'),(100,'#e1ded6')]),('cubeSide',side,[(0,'#87857c'),(100,'#55574f')])]:
        fill=d.gradient(name,colors,extra='x1="0" y1="0" x2="1" y2="1"')
        d.path(p,fill,stroke='#77766e',width=1.3,opacity=.83 if rev==1 else 1)
        d.hatch(p,name+'Hatch',.04 if rev==1 else (.11 if name=='cubeSide' else .035),8,rev>=3)
    d.end()
    d.group(f'transform="translate({r.randint(-25,25)} {r.randint(-8,12)})"')
    pear='M828 629 C826 608 840 593 852 590 C857 566 875 564 882 588 C889 610 920 622 929 649 C948 703 851 742 816 698 C801 681 812 649 828 629 Z'
    pf=d.gradient('pear',[(0,'#ece7d9'),(25,'#d3ccb8'),(62,'#aaa38e'),(100,'#666657')],True,extra='cx="28%" cy="26%" r="76%"')
    d.ellipse(881,704,83,17,'#3e3a31',.12 if rev==1 else .22)
    d.path(pear,pf,stroke='#706e62',opacity=.85)
    d.hatch(pear,'pearHatch',.055 if rev==1 else .09,6)
    d.path('M861 592 Q855 568 873 549',stroke='#575344',width=6)
    d.path('M855 626 Q834 660 840 676',stroke='#f7f1df',width=5,opacity=.3 if rev<3 else .8)
    d.end()
    if advanced:
        glass='M172 421 Q232 405 273 427 L266 641 Q229 675 188 646 Z'
        d.path(glass,fill='#ebe8dc',opacity=.28)
        d.ellipse(224,424,51,12,'none',extra='stroke="#76796f" stroke-width="1.4"')
        d.path('M175 432 L188 642 Q229 667 265 640 L271 434',stroke='#6d736d',width=1.4,opacity=.85 if rev==1 else .48)
        d.path('M182 443 L195 626 M244 444 L240 620',stroke='#ffffff',width=4,opacity=.65)
        d.path('M193 559 Q226 578 263 562 M194 571 Q230 586 263 573',stroke='#8f9285',width=2,opacity=.65)
        d.ellipse(227,646,37,8,'none',extra='stroke="#8b8c82" stroke-width="2"')
    if rev==1:
        d.path('M425 238 V664 M286 449 H555 M593 454 L910 504 M608 621 L894 671',stroke='#746f61',width=.8,opacity=.22)
        d.path('M280 661 Q578 707 949 725',stroke='#8e8878',width=1,opacity=.3)
    elif rev>=3:d.path('M329 595 Q412 656 516 609 M615 616 L789 658',stroke='#34332e',width=2,opacity=.45)
    d.end()


def leaf(d,x,y,angle,length,width,color,name,revision):
    d.group(f'transform="translate({x:.1f} {y:.1f}) rotate({angle:.1f})"')
    shape=f'M0 0 C{-width*.7:.1f} {-length*.3:.1f} {-width*.5:.1f} {-length*.76:.1f} 0 {-length} C{width*.65:.1f} {-length*.75:.1f} {width*.8:.1f} {-length*.28:.1f} 0 0 Z'
    fill=d.gradient(name,[(0,color[0]),(45,color[1]),(100,color[2])],extra='x1="0" y1="0" x2="1" y2=".25"')
    d.path(shape,fill,opacity=.67 if revision==1 else .82)
    for j in range(3):d.path(shape,stroke=color[0],width=.8+j*.6,opacity=.1,extra=f'transform="translate({j*.6} {j*.3})"')
    d.path(f'M0 0 Q-5 {-length*.5:.1f} 0 {-length+8}',stroke=color[2],width=1.3,opacity=.38 if revision==1 else .65)
    for t in [.23,.37,.51,.65,.77]:
        yy=-length*t
        for side in [-1,1]:d.path(f'M-2 {yy:.1f} Q{side*width*.22:.1f} {yy-length*.025:.1f} {side*width*.39:.1f} {yy-length*.095:.1f}',stroke=color[2],width=.6,opacity=.15 if revision==1 else .37)
    if revision>=2:
        for _ in range(22):
            yy=-d.rng.uniform(length*.16,length*.88);xx=d.rng.uniform(-width*.28,width*.28)
            d.ellipse(xx,yy,d.rng.uniform(2,9),d.rng.uniform(3,14),color[d.rng.choice([0,2])],.04)
    if revision>=3:d.path(f'M1 -5 Q-5 {-length*.48:.1f} 0 {-length*.91:.1f}',stroke='#f8e9ac',width=1.4,opacity=.6)
    d.end()


def watercolor(d):
    paper(d,'#faf7ef');r=d.rng;rev=d.revision
    bend=r.randint(-65,65);start=485+bend
    for _ in range(11):
        x,y=r.uniform(350,805),r.uniform(220,690)
        d.ellipse(x,y,r.uniform(40,95),r.uniform(30,68),'#cdb990',.025 if rev==1 else .04)
    d.path(f'M{start} 753 Q{590+bend} 467 {635+bend} 161',stroke='#9a8654',width=4.5,opacity=.7)
    d.path(f'M{start+3} 748 Q{595+bend} 465 {637+bend} 164',stroke='#d5b679',width=1.6,opacity=.8)
    palettes=[('#d5ac59','#d89143','#8b6839'),('#e3c57b','#cf894f','#93623f'),('#b6b377','#8d9c68','#666f4b'),('#cd8250','#ad613b','#795136')]
    specs=[]
    for i in range(9):
        y=655-i*55;x=start+(753-y)*.265;side=-1 if i%2 else 1
        a=side*(62+r.uniform(-9,12));length=r.uniform(137,203)*(1-i*.035);width=length*r.uniform(.4,.58)
        specs.append((x,y,a,length,width,palettes[(i+r.randint(0,2))%4]))
    for i,(x,y,a,length,width,pal) in enumerate(specs):leaf(d,x,y,a,length,width,pal,f'leaf{i}',rev)
    leaf(d,635+bend,174,6,95,46,palettes[1],'topLeaf',rev)
    leaf(d,820-bend/2,739,-116,155,82,palettes[3],'fallen',rev)
    d.path('M286 748 Q358 776 431 757',stroke='#b3a98b',width=1,opacity=.3)
    if rev==1:
        for x,y in [(441,499),(649,398),(589,583)]:d.ellipse(x+bend*.5,y,29,18,'#9f6947',.10)


def bamboo(d):
    paper(d,'#eee8d8');r=d.rng;rev=d.revision
    rock='M72 769 L114 652 L183 584 L229 633 L278 607 L342 680 L372 780 Z'
    d.path(rock,fill='#777b69',opacity=.24)
    d.path('M86 754 L179 596 L162 696 L215 677 L276 618 L254 735 L340 770',stroke='#484d43',width=3,opacity=.34)
    for _ in range(70):
        x,y=r.uniform(109,323),r.uniform(677,763)
        d.path(f'M{x:.1f} {y:.1f} l{r.uniform(-16,16):.1f} {-r.uniform(3,18):.1f}',stroke='#313e30',width=r.uniform(.6,2),opacity=.15)
    for s,(x,y,slope,length,width) in enumerate([(304,788,-.23,650,15),(542,787,-.055,730,19),(718,799,.18,591,13)]):
        for i in range(6):
            y1=y-i*length/6;y2=y-(i+1)*length/6+7;x1=x+(y-y1)*slope;x2=x+(y-y2)*slope
            d.path(f'M{x1:.1f} {y1:.1f} Q{x1-5:.1f} {(y1+y2)/2:.1f} {x2:.1f} {y2:.1f}',stroke='#354c3a',width=width,opacity=.62 if s==1 else .33)
            d.path(f'M{x1-3:.1f} {y1-3:.1f} L{x2-3:.1f} {y2+4:.1f}',stroke='#abb194',width=2.4,opacity=.44)
            d.path(f'M{x2-width*.65:.1f} {y2:.1f} q{width*.7:.1f} -6 {width*1.3:.1f} 1',stroke='#233c2c',width=2.3,opacity=.75)
    for gi,(x,y,side) in enumerate([(285,453,-1),(447,306,1),(527,502,-1),(665,490,1),(510,185,1),(798,351,1),(375,661,-1)]):
        shift=0 if rev==1 else (gi%3-1)*14
        d.path(f'M{x} {y} Q{x+side*70} {y-30} {x+side*174} {y-45}',stroke='#283d2d',width=2.4,opacity=.69)
        lr=random.Random(gi*91+int(r.random()*100))
        for j in range(9 if rev==1 else 12):
            bx=x+side*(20+j*11);by=y-10-j*2
            angle=side*(25+(j%4)*29)+shift+lr.uniform(-13,13);ln=lr.uniform(52,108);wd=lr.uniform(7,12)
            if rev==1:angle=side*(42+(j%3)*27)
            d.group(f'transform="translate({bx} {by}) rotate({angle})"')
            opacity=lr.uniform(.35,.86) if rev>1 else lr.uniform(.42,.65)
            d.path(f'M0 0 Q{-wd} {-ln*.37} 3 {-ln} Q{wd} {-ln*.38} 0 0',fill='#243c2e',opacity=opacity)
            if rev>=3:d.path(f'M1 -4 L3 {-ln+6}',stroke='#a9b297',width=.5,opacity=.4)
            d.end()
    if rev>1:
        for i in range(16):
            x=190+i*9;y=788-r.randint(0,9)
            d.path(f'M{x} {y} Q{x-r.randint(5,25)} {y-43} {x-r.randint(9,42)} {y-r.randint(51,85)}',stroke='#46523b',width=1.2,opacity=.55)


def landscape(d):
    r=d.rng;rev=d.revision;paper(d,'#e9e3cf')
    palettes=[('#c4d5d9','#e6dac0','#5d7e85','#58715f','#b2ae71','#355e51'),('#b9ced0','#e6ddca','#667f8a','#7c896c','#c3ae75','#4a6858'),('#c3d2dd','#f0dec5','#647e92','#7d927c','#b2b178','#385e5f'),('#bfd1c9','#e8dfbe','#6e8181','#7f865a','#d1b575','#4e6451')]
    sky,warm,far,middle,grass,deep=palettes[r.randrange(len(palettes))]
    sf=d.gradient('sky',[(0,sky),(100,warm)],extra='x1="0" y1="0" x2="0" y2="1"')
    d.defs.append('<clipPath id="canvas"><rect x="65" y="65" width="1070" height="766"/></clipPath>')
    d.group('clip-path="url(#canvas)"');d.add(f'<rect x="65" y="65" width="1070" height="766" fill="{sf}"/>')
    cr=random.Random(r.randint(1,9999))
    for _ in range(175):
        x=cr.uniform(70,1100);y=cr.uniform(118,351)
        d.path(f'M{x:.1f} {y:.1f} q{cr.uniform(20,65):.1f} -5 {cr.uniform(55,170):.1f} -2',stroke='#faf0db',width=cr.uniform(3,13),opacity=.025 if rev==1 else .06)
    peaks=[(60,420),(132,402),(201,365),(272,288+r.randint(-25,25)),(304,309),(343,350),(393,373),(460,341),(518,353),(565,323+r.randint(-20,20)),(599,353),(688,388),(748,381),(826,324+r.randint(-20,20)),(867,337),(920,375),(984,347),(1043,385),(1140,415)]
    mountain='M65 503 '+' '.join(f'L{x} {y}' for x,y in peaks)+' L1140 529 Z'
    d.path(mountain,far,opacity=.72)
    d.defs.append(f'<clipPath id="mountainTexture"><path d="{mountain}"/></clipPath>')
    d.group('clip-path="url(#mountainTexture)"')
    for i,(x,y) in enumerate(peaks[1:-1]):
        if i%3:continue
        d.path(f'M{x} {y} l-17 49 14 14 -22 63 72 59 -8 -101 Z',fill=warm,opacity=.055 if rev==1 else .12)
        for j in range(10):d.path(f'M{x-25+j*9} {y+25+j*3} q-18 40 -11 85',stroke=deep,width=1.5,opacity=.035)
    d.end()
    mist=d.gradient('mist',[(0,sky),(100,warm)],extra='x1="0" y1="0" x2="0" y2="1"')
    d.path('M65 457 Q278 478 488 462 T1135 470 L1135 540 H65 Z',mist,opacity=.32)
    d.path('M61 528 Q181 480 256 493 T465 477 Q564 440 670 491 Q836 440 925 465 T1142 491 L1145 704 L60 693 Z',middle)
    d.path('M50 661 Q198 595 290 566 Q367 548 441 581 T614 624 Q771 604 861 537 Q968 508 1147 620 L1145 873 L54 869 Z',grass)
    d.path('M622 843 Q688 733 703 676 Q706 628 786 566 Q735 632 763 674 Q789 733 812 844 Z',fill='#d4c09b',opacity=.85)
    d.path('M621 843 Q668 733 703 676 Q702 627 783 568',stroke='#eee0b6',width=5,opacity=.55)
    d.path('M73 700 Q230 622 357 653 Q489 699 598 751 L569 850 H61 Z',deep,opacity=.5 if rev==1 else .88)
    rng=random.Random(402+int(r.random()*10000))
    for i in range(1800):
        x=rng.uniform(65,1135);y=rng.uniform(475,850)
        if y<530 and x<450:continue
        col=rng.choice([grass,deep,middle,warm,'#d7bf7e','#6b7958'])
        if rev==1:col=rng.choice([grass,middle,deep])
        d.path(f'M{x:.1f} {y:.1f} l{rng.uniform(3,19):.1f} {rng.uniform(-6,1):.1f}',stroke=col,width=rng.uniform(1.7,5.5),opacity=rng.uniform(.06,.23))
    tx=r.choice([175,227,992,1040])
    d.path(f'M{tx} 758 Q{tx+8} 623 {tx-13} 454 M{tx-2} 628 l-42 -82 M{tx+1} 602 l43 -79',stroke='#3e5246',width=9,opacity=.75)
    for j in range(270):
        angle=r.random()*math.tau;rr=math.sqrt(r.random());x=tx+math.cos(angle)*110*rr;y=515+math.sin(angle)*112*rr
        col=r.choice([deep,middle,'#6c8055',grass])
        d.path(f'M{x:.1f} {y:.1f} l{r.uniform(4,17):.1f} {-r.uniform(2,9):.1f}',stroke=col,width=r.uniform(3,10),opacity=r.uniform(.22,.60))
    # Low hedges recede into the valley, with progressively finer brush marks.
    for i in range(18):
        x=97+i*56;y=544+math.sin(i*.72)*16
        for j in range(8):d.path(f'M{x+j*2} {y+r.uniform(-8,8):.1f} l{r.uniform(2,7):.1f} -2',stroke=deep,width=2,opacity=.23)
    if rev>=3:
        for _ in range(40):
            x=r.uniform(77,1117);y=r.uniform(686,816);d.path(f'M{x:.1f} {y:.1f} l7 -16',stroke='#d2c18d',width=2,opacity=.65)
    for x in range(65,1136,4):d.path(f'M{x} 65 V831',stroke='#ffffff',width=.4,opacity=.04)
    for y in range(65,832,4):d.path(f'M65 {y} H1135',stroke='#493e2b',width=.4,opacity=.035)
    d.end()


def garden(d):
    r=d.rng;rev=d.revision;paper(d,'#fbf6e7')
    d.path('M89 725 Q387 672 592 742 T1108 718 L1108 807 H89 Z',fill='#bac7a0',opacity=.6)
    colors=['#d38977','#d5ae58','#7c9eaa','#a4ae7f','#bb8993'];specs=[]
    for i in range(8):specs.append((151+i*123+r.randint(-19,18),r.randint(282,535),r.randint(30,57),colors[(i+r.randint(0,3))%5]))
    for i,(x,y,rad,col) in enumerate(specs):
        base=x+r.randint(-27,23)
        d.path(f'M{base} 754 Q{x+24} {y+110} {x} {y}',stroke='#70885d',width=4.5,opacity=.72)
        for off,side in [(85,-1),(140,1)]:
            yy=y+off;d.path(f'M{x+7} {yy+22} Q{x+side*66} {yy-30} {x+side*75} {yy-24} Q{x+side*49} {yy+35} {x+7} {yy+22}',fill='#839963',opacity=.7)
            d.path(f'M{x+8} {yy+20} l{side*59} -39',stroke='#546d49',width=.9,opacity=.6)
        petals=8 if i%2 else 11
        d.group(f'transform="translate({x} {y}) rotate({r.randint(-20,20)})"')
        for j in range(petals):
            d.group(f'transform="rotate({j*360/petals})"')
            d.path(f'M-8 2 C{-rad*.6} {-rad*.5} {-rad*.5} {-rad*1.5} 0 {-rad*1.6} C{rad*.54} {-rad*1.43} {rad*.55} {-rad*.36} 8 2 Z',fill=col,stroke='#806452',width=.8,opacity=.75)
            if rev>1:d.path(f'M0 -9 Q-7 {-rad*.8} 0 {-rad*1.33}',stroke='#fff3cf',width=1.3,opacity=.6)
            d.end()
        d.ellipse(0,0,rad*.37,rad*.35,'#96764c',.9)
        for j in range(17):
            angle=j*2.4;rr=math.sqrt(j/17)*rad*.27;d.ellipse(math.cos(angle)*rr,math.sin(angle)*rr,1.3,1.5,'#f0cc7b',.85)
        d.end()
    bx=r.randint(440,705);by=194+r.randint(-25,25)
    d.path(f'M{bx} {by} C{bx-70} {by-59} {bx-73} {by+12} {bx-5} {by+19} C{bx-38} {by+67} {bx+2} {by+65} {bx+7} {by+18} C{bx+76} {by+57} {bx+76} {by-12} {bx+8} {by+4} C{bx+44} {by-58} {bx+4} {by-66} {bx} {by}',fill='#d6ab62',stroke='#9c8057',width=1.2,opacity=.8)
    d.path(f'M{bx} {by-3} l10 29 m-7 -22 l-8 -16 m8 16 l7 -17',stroke='#645d46',width=2)
    if rev>=2:
        for i in range(12):
            x=124+i*88;y=768+r.randint(-9,9);d.path(f'M{x} {y} q-8 -25 -18 -39 M{x} {y} q11 -29 24 -41',stroke='#597b54',width=1.5,opacity=.7)
    if rev==1:
        for x,y,rad,col in specs:d.ellipse(x,y,rad*1.7,rad*1.7,'none',opacity=.18,extra='stroke="#a39680" stroke-width=".7"')


def draw_exercise(path,category,seed,revision=1):
    """Write a 1400x1050 PNG and an editable self-contained SVG source."""
    import cairosvg
    from io import BytesIO
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True);d=Drawing(seed,revision)
    if category in ('sketch','advanced'):sketch(d,category=='advanced')
    elif category=='water':watercolor(d)
    elif category=='ink':bamboo(d)
    elif category=='creative':garden(d)
    elif category=='oil':landscape(d)
    else:raise ValueError('Unknown teaching category: '+category)
    source=d.svg();path.with_suffix('.svg').write_text(source,encoding='utf-8')
    raw=cairosvg.svg2png(bytestring=source.encode(),output_width=1400,output_height=1050)
    image=Image.open(BytesIO(raw)).convert('RGB').quantize(colors=256,method=Image.Quantize.MEDIANCUT,dither=Image.Dither.NONE)
    image.save(path,optimize=True)
    return path


if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,default=Path('work/artwork-preview'));args=p.parse_args()
    for i,category in enumerate(['sketch','advanced','water','creative','ink','oil']):
        for rev in (1,2,3):draw_exercise(args.output/f'{category}-v{rev}.png',category,27+i,rev)
    print('Rendered 18 original SVG/PNG study previews.')
