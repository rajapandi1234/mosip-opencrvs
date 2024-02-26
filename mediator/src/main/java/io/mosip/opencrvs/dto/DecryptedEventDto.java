package io.mosip.opencrvs.dto;

import java.util.List;
import java.util.Map;

public class DecryptedEventDto {
    public static class Event {
        public static class Context {
            public static class Entry {
            	public static class Coding {
            		public String system;
            		public String code;
            	}
                public static class Resource {
                    public static class Focus {
                        public String reference;
                    }
                    public static class Identifier{
                    	public static class Type{
                            public List<Coding> coding;
                    	}
                        public Type type;
                        public String system;
                        public String value;
                    }
                    public static class Extension{
                        public String url;
                        public String valueString;
                    }
                    public static class Name{
                        public String use;
                        public List<String> given;
                        public List<String> family;
                    }

                    public String resourceType;
                    public Focus focus;
                    public List<Name> name;
                    public String gender;
                    public String birthDate;
                    public List<Map<String,Object>> address;
                    public List<Identifier> identifier;
                    public List<Extension> extension;
                }
                public Resource resource;
            }
            public List<Entry> entry;
        }
        public List<Context> context;
    }

    public String id;
    public String timestamp;
    public Event event;
}
