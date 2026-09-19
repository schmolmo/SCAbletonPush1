AbletonPush1 {
	var <>server, <simulation, <midiOut, midiIn;
	var <padMode, padColorCache, <padScale, <rowInterval;
	var <>noteOnFunc, <>noteOffFunc, <>afterTouchFunc, <>ribbonFunc, <>pedal1Func;
	var xOffset, yOffset, <>noteVelocities;
	var <encoderObjects, <encoderKeys, <>encoderPage, <encoderValues;
	var <>displayCache;
	var <>highlightPads;

	*new {|server, simulation=false|
		^super.newCopyArgs(server, simulation).init()
	}

	*getProgressBar {|value| // between 0-1.0
		var steps = (value.clip(0, 1) * 32).trunc.asInteger;
		^Array.fill(8, { |i|
			var local = (steps - (i * 4)).clip(0, 4);
			switch(local,
				0, { 6 }, // --
				1, { 4 }, // -|
				2, { 3 }, // |-
				3, { 5 }, // ||
				4, { 5 }  // ||
			)
		})
	}

	init {
		MIDIClient.init(); MIDIIn.connectAll;
		switch(thisProcess.platform.name)
		{\osx} {
			"mac os".postln;
			if(simulation.not, {
				midiOut = MIDIOut.newByName("Ableton Push", "User Port");
				midiIn = MIDIIn.findPort("Ableton Push", "User Port");
			}, {
				midiOut = MIDIOut.newByName("Internal MIDI", "to DAW");
				midiIn = MIDIIn.findPort("Internal MIDI", "to DAW");
			});
		}
		{\linux } {
			"linux".postln;
			midiOut = MIDIOut.findPort("Ableton Push", "Ableton Push User Port");
			midiOut.latency_(0);
			midiIn = MIDIIn.findPort("Ableton Push", "Ableton Push User Port");
		};

		(44..47).do{|i| midiOut.control(0, i, 4) }; // turn on navigation arrows
		[50,51,54,55, 62,63].do{|i| midiOut.control(0, i, 4) }; // turn on octave up / down, note/session
		midiOut.sysex(Int8Array[240,71,127,21,92,0,1,0,247]); // set note aftertouch
		midiOut.sysex(Int8Array[240,71,127,21,99,0,1,9 /*0-10*/ ,247]); // set ribbon to modwheel

		xOffset = 0; yOffset = 0;
		padColorCache = (0!3)!64;
		highlightPads = List[];
		noteVelocities = 0!128;

		encoderPage = 0;
		encoderObjects = List.newClear(64); encoderKeys = List.newClear(64);
		encoderValues = List.newClear(64);
		displayCache = 32!68!4; // 32=Char.space.ascii

		this.padMode = \note;
		this.makeMidiFuncs;
		this.clearDisplay;

	}

	makeMidiFuncs {
		MIDIdef.cc(\nav, {|val, cc|
			if(val ==127) {
				switch(cc,
					44, {xOffset = xOffset-1},
					45, {xOffset = xOffset+1},
					46, {yOffset = yOffset+1},
					47, {yOffset = yOffset-1},
					54, {
						yOffset = yOffset-2;
						xOffset = xOffset-2;
					},
					55, {
						yOffset = yOffset+2;
						xOffset = xOffset+2;
					},
					50, { this.padMode_(\note) },
					51, { this.padMode_(\session) },
					62, { encoderPage = encoderPage + 1 },
					63, { encoderPage = encoderPage - 1 },
				);
				this.updatePads;
				// this.updateDisplay;
			};
		}).permanent_(true);

		MIDIdef.noteOn(\padOn, {|vel, note|
			var x,y;
			note = note - 36;
			x = note % 8;
			y = (note/8).trunc.asInteger;
			if(padMode == \note, {
				note = (x + xOffset) + ((y+yOffset)*rowInterval);
				noteVelocities[note] = vel;
				noteOnFunc !? { noteOnFunc.(vel, note, padScale.degreeToFreq(note, 0.midicps, 0)) };
			});
		}, (36..99),0).permanent_(true);

		MIDIdef.noteOff(\padOff, {|vel, note|
			var x,y;
			note = note - 36;
			x = note % 8;
			y = (note/8).trunc.asInteger;
			if(padMode == \note, {
				note = (x + xOffset) + ((y+yOffset)*rowInterval);
				noteVelocities[note] = vel;
				noteOffFunc !? { noteOffFunc.(vel, note) };
			});
		}, (36..99),0).permanent_(true);

		MIDIdef.polytouch(\aftertouch, {|vel, note|
			var x,y;
			note = note - 36;
			x = note % 8;
			y = (note/8).trunc.asInteger;
			if(padMode == \note, {
				note = (x + xOffset) + ((y+yOffset)*rowInterval);
				noteVelocities[note] = vel;
				afterTouchFunc !? { afterTouchFunc.(vel, note) };
			});
		}, (36..99), 0).permanent_(true);

		MIDIdef.cc(\ribbon, {|val|
			ribbonFunc !? { ribbonFunc.(val.linlin(0, 127,0, 1.0)) }
		}, 1,0).permanent_(true);

		MIDIdef.cc(\encoders, {|val, num|
			var obj, spec, key, nodeVal, res;
			var delta, unmapped;
			num = num-71;
			val = if(val > 64, { val-128 }, { val });
			if(val!=0, {
				key = encoderKeys[num+(encoderPage*8)];
				obj = encoderObjects[num+(encoderPage*8)];
				if(obj.notNil and: { key.notNil }, {
					spec = obj.specs[key].asSpec;
					nodeVal = obj.get(key);
					delta = val * 0.01;
					unmapped = spec.unmap(nodeVal);
					res = spec.map(unmapped + delta);
					obj.set(key, res);
					this.updateDisplayValue(num, res.asArray[0], spec);
				});
			});
		}, (71..78)).permanent_(true);

		MIDIdef.cc(\volume, {|val|
			val = if(val > 64, { val-127 }, { val });
			if(val!=0, {
				server.volume.volume = (server.volume.volume + val.linlin(-10,10.0, -2, 2.0));
			});
		}, 79, 0).permanent_(true);

		Tdef(\updateDisplay, {
			{ this.updateDisplay; 0.5.wait }.loop
		}).play;

		MIDIdef.cc(\pedal1, {|val| pedal1Func.value(val) },69,0);

		CmdPeriod.add({ Tdef(\updateDisplay).play });
	}

	// pads

	padMode_{ |mode| // note or session
		padMode = mode;
		xOffset = 0; yOffset = 0;
		this.updatePads
	}

	updatePads{
		switch(padMode,
			\note, {
				var padNum;
				padScale = padScale ? Scale.chromatic;
				rowInterval = rowInterval ? 5;
				8.do{|y|
					8.do{|x|
						var note, thisColor;
						padNum = (y*8) + x;
						note = (x + xOffset) + ((y+yOffset)*rowInterval);
						thisColor = if(highlightPads.includes(note), { [0,64, 127] }, {
							if(note % padScale.degrees.size == 0,{[0, 0, 127] }, { 127!3 })
						});
						this.setPadColor(padNum.clip(0,63), *thisColor);
					};
				}
			},
			\session, { this.clearPads }
		);

	}

	setPadColor {|padNum, r, g, b|
		var r1 = (r/16).round;
		var r2 = r % 16;
		var g1 = (g/16).round;
		var g2 = g % 16;
		var b1 = (b/16).round;
		var b2 = b % 16;
		if(padColorCache[padNum] != [r,g,b], {
			midiOut.sysex(Int8Array[240,71,127,21,4,0,8,padNum,0,r1, r2, g1, g2, b1, b2, 247]);
			padColorCache[padNum] = [r,g,b]
		});
	}

	clearPads {
		64.do { |i| this.setPadColor(i, 0, 0, 0) }
	}

	setPadScale { |scale1, rowInterval1|
		padScale = scale1; rowInterval = rowInterval1;
		this.updatePads
	}

	notePressed { ^noteVelocities.select{|itm| itm > 0 }.notEmpty  }

	// display / encoders
	writeString {|row, block, string|
		var offset = #[0,9,17,26,34,43,51,60][block];
		var ascii = string.ascii;

		// update single chars
		ascii.do{|char, indx|
			indx = indx+offset;
			if(displayCache[row][indx]!=char, {
				midiOut.sysex(Int8Array.newFrom([240,71,127,21,24+row,0,1+1,indx,char,247]));
				displayCache[row][indx] = char;
			});
		}

		//update whole string
		/*if(displayCache[row][offset..(offset+string.size-1)] != ascii, {
		midiOut.sysex(
		Int8Array.newFrom([240,71,127,21,24+row,0,ascii.size+1,offset,ascii,247].flatten)
		);
		ascii.do{|char, indx| displayCache[row][indx+offset] = char };
		});*/

	}

	writeAscii {|row, block, ascii|
		var offset = #[0,9,17,26,34,43,51,60][block];
		if(displayCache[row][offset..(offset+ascii.size-1)] != ascii) {
			midiOut.sysex( Int8Array.newFrom([240,71,127,21,24+row,0,ascii.size+1,offset,ascii,247].flatten) );
			ascii.do{|char, indx| displayCache[row][indx+offset] = char };
		};
	}
	clearLine { |line|
		midiOut.sysex(Int8Array[240,71,127,21,28+line,0,0,247]);
	}
	clearDisplay{
		4.do{ |l| this.clearLine(l) }
	}

	clearBlock{ |row, block|
		this.writeString(row, block, "        ")
	}

	// this takes the displayed value from the object via obj.get
	updateDisplay {
		var keys = encoderKeys[(encoderPage*8)..((encoderPage*8)+7)];
		var objects = encoderObjects[(encoderPage*8)..((encoderPage*8)+7)];
		var value;
		objects.do {|obj, i|
			var key = keys[i];
			value = obj.get(key).asArray[0];
			if(obj.notNil and:{ key.notNil }, {
				this.writeString(0, i, obj.key.asString[0..7].padRight(8));
				this.writeString(1, i, key.asString.padRight(8)[0..7]);
				value !? {
					this.writeAscii(2, i, AbletonPush1.getProgressBar(obj.specs[key].unmap(value)));
					this.writeString(3, i, value.asFloat.asStringPrec(8).padRight(8))
				}
			}, { (0..3).do{|rw| this.clearBlock(rw, i)} });
		}
	}

	// this updates the given value
	updateDisplayValue{ |slot, value, spec|
		this.writeAscii(2, slot%8, AbletonPush1.getProgressBar(spec.unmap(value)));
		this.writeString(3, slot%8, value.asStringPrec(5))
	}

	addControlObj {|slot, obj, key|
		encoderObjects[slot] = obj;
		encoderKeys[slot] = key;
		encoderValues[slot] = obj.get(key).asArray[0];
		this.updateDisplay;
	}

	addObject{|slot, obj, keys| keys.do { |key, i| this.addControlObj(slot+i, obj, key) } }

}